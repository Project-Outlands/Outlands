package com.cleanroommc.lwjgl.legacy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies {@link RewriteTable} to call sites: stack-neutral retargeting plus straight-line argument conversion,
 * so existing StackMapTables stay valid and only {@code COMPUTE_MAXS} is needed.
 */
public final class CompatRewriter {

    private CompatRewriter() {}

    private static final String MEM_ADDRESS_OWNER = "org/lwjgl/system/MemoryUtil";
    private static final String MEM_ADDRESS_NAME = "memAddress";
    private static final String MEM_ADDRESS_DESC = "(Ljava/nio/Buffer;)J";

    /** Returns the rewritten class or {@code null} when nothing matched. */
    public static byte[] rewrite(byte[] classBytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(classBytes).accept(cn, 0);

        boolean changed = false;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions != null && mn.instructions.size() > 0) {
                changed |= rewriteMethod(mn);
            }
        }
        if (!changed) {
            return null;
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static boolean rewriteMethod(MethodNode mn) {
        boolean changed = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                changed |= rewriteCall(mn, (MethodInsnNode) insn);
            }
            insn = next;
        }
        return changed;
    }

    private static boolean rewriteCall(MethodNode mn, MethodInsnNode call) {
        String key = RewriteTable.key(call.owner, call.name, call.desc);

        String newName = RewriteTable.RENAME.get(key);
        if (newName != null) {
            call.name = newName;
            return true;
        }

        String alias = RewriteTable.ALIAS.get(key);
        if (alias != null) {
            int sep = alias.indexOf('\0');
            call.owner = alias.substring(0, sep);
            call.name = alias.substring(sep + 1);
            return true;
        }

        String convert = RewriteTable.CONVERT.get(key);
        if (convert != null) {
            return convert(mn, call, convert);
        }

        // CTOR/ADAPTER entries are handled once the adapter class exists; until then the shim layer covers them.
        return false;
    }

    private static boolean convert(MethodNode mn, MethodInsnNode call, String payload) {
        String[] parts = payload.split("\0", -1);
        if (parts.length != 4) {
            return false;
        }
        String targetOwner = parts[0];
        String targetName = parts[1];
        String targetDesc = parts[2];
        String recipe = parts[3];

        Type[] src = Type.getArgumentTypes(call.desc);
        Type[] dst = Type.getArgumentTypes(targetDesc);

        Action[] plan = new Action[dst.length];
        boolean[] dropped = new boolean[src.length];
        for (String op : recipe.split(";")) {
            if (op.isEmpty()) {
                continue;
            }
            String[] f = op.split(":");
            switch (f[0]) {
                case "insConst":
                    plan[Integer.parseInt(f[1])] = Action.constant(f[2]);
                    break;
                case "typeFromUnsigned":
                    plan[Integer.parseInt(f[1])] = Action.unsignedType(f.length > 2 ? f[2] : "org/lwjgl/opengl/GL11/GL_BYTE");
                    break;
                case "widen":
                    plan[Integer.parseInt(f[1])] = Action.WIDEN;
                    break;
                case "addr":
                    plan[Integer.parseInt(f[1])] = Action.ADDRESS;
                    break;
                case "drop":
                    dropped[Integer.parseInt(f[1])] = true;
                    break;
                default:
                    throw new IllegalArgumentException("unknown recipe op: " + op);
            }
        }

        int cursor = 0;
        int[] from = new int[dst.length];
        java.util.Arrays.fill(from, -1);
        for (int j = 0; j < dst.length; j++) {
            if (plan[j] != null && plan[j].kind == ActionKind.CONST) {
                continue;
            }
            while (cursor < src.length && dropped[cursor]) {
                cursor++;
            }
            if (cursor >= src.length) {
                throw new IllegalStateException("recipe/arity mismatch: " + recipe + " for " + call.owner + '.'
                        + call.name + call.desc);
            }
            from[j] = cursor++;
            if (plan[j] == null) {
                plan[j] = Action.LOAD;
            }
            if (plan[j].kind == ActionKind.ADDRESS) {
                if (dst[j].getSort() != Type.LONG) {
                    throw new IllegalStateException("addr target is not long: " + targetDesc);
                }
            } else if (dst[j].getSize() != src[from[j]].getSize()) {
                throw new IllegalStateException("slot size mismatch at " + j + " for " + targetDesc);
            }
        }

        // Spill every argument to fresh locals, then push them back in target order with the conversions applied.
        InsnList out = new InsnList();
        int local = mn.maxLocals;
        int[] slots = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            slots[i] = local;
            local += src[i].getSize();
        }
        mn.maxLocals = local;

        for (int i = src.length - 1; i >= 0; i--) {
            out.add(new VarInsnNode(src[i].getOpcode(Opcodes.ISTORE), slots[i]));
        }
        for (int j = 0; j < dst.length; j++) {
            switch (plan[j].kind) {
                case CONST:
                    out.add(new FieldInsnNode(Opcodes.GETSTATIC, plan[j].owner, plan[j].name, "I"));
                    break;
                case UNSIGNED_TYPE:
                    // GL_BYTE(0x1400) + unsigned == GL_UNSIGNED_BYTE(0x1401), and so on for SHORT/INT
                    out.add(new VarInsnNode(Opcodes.ILOAD, slots[from[j]]));
                    out.add(new FieldInsnNode(Opcodes.GETSTATIC, plan[j].owner, plan[j].name, "I"));
                    out.add(new InsnNode(Opcodes.IADD));
                    break;
                case WIDEN:
                    out.add(new VarInsnNode(Opcodes.ILOAD, slots[from[j]]));
                    out.add(new InsnNode(Opcodes.I2L));
                    break;
                case ADDRESS:
                    out.add(new VarInsnNode(Opcodes.ALOAD, slots[from[j]]));
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MEM_ADDRESS_OWNER, MEM_ADDRESS_NAME,
                            MEM_ADDRESS_DESC, false));
                    break;
                case LOAD:
                default:
                    out.add(new VarInsnNode(dst[j].getOpcode(Opcodes.ILOAD), slots[from[j]]));
                    break;
            }
        }
        out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, targetOwner, targetName, targetDesc, false));

        mn.instructions.insertBefore(call, out);
        mn.instructions.remove(call);
        return true;
    }

    private enum ActionKind { LOAD, CONST, UNSIGNED_TYPE, WIDEN, ADDRESS }

    private static final class Action {
        static final Action LOAD = new Action(ActionKind.LOAD, null, null);
        static final Action WIDEN = new Action(ActionKind.WIDEN, null, null);
        static final Action ADDRESS = new Action(ActionKind.ADDRESS, null, null);

        final ActionKind kind;
        final String owner;
        final String name;

        private Action(ActionKind kind, String owner, String name) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
        }

        static Action constant(String ownerAndName) {
            int sep = ownerAndName.lastIndexOf('/');
            return new Action(ActionKind.CONST, ownerAndName.substring(0, sep), ownerAndName.substring(sep + 1));
        }

        static Action unsignedType(String baseConst) {
            int sep = baseConst.lastIndexOf('/');
            return new Action(ActionKind.UNSIGNED_TYPE, baseConst.substring(0, sep), baseConst.substring(sep + 1));
        }
    }
}
