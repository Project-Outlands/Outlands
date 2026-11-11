package net.minecraftforge.client.render.vulkan.shader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a SPIR-V module declares, in the terms a GL implementation has to answer in.
 *
 * <p><b>Why this is needed at all.</b> GL binds attributes and uniforms by name: {@code glGetUniformLocation(program,
 * "DiffuseSampler")} is how the game's {@code ShaderManager} finds anything. SPIR-V has no names. So the names, and
 * the locations and bindings the compiler assigned to them, have to be read back out of the module - that is this
 * class. Everything else about the shader path depends on it, which is why it exists before any of the rest.</p>
 *
 * <p><b>What is read.</b> The uniform variables and their decorations ({@code DescriptorSet}, {@code Binding}), the
 * uniform blocks' members with their offsets, and the declared size of each member's type. That is the minimum both
 * ends need: the descriptor set layout when the pipeline is built, and the byte layout when the game hands over a
 * {@code glUniform4f}.</p>
 */
public final class SpirvReflection {

    private static final int MAGIC = 0x07230203;

    // Instruction opcodes, from the SPIR-V specification.
    private static final int OP_NAME = 5;
    private static final int OP_ENTRY_POINT = 15;
    private static final int OP_MEMBER_NAME = 6;
    private static final int OP_TYPE_BOOL = 20;
    private static final int OP_TYPE_INT = 21;
    private static final int OP_TYPE_FLOAT = 22;
    private static final int OP_TYPE_VECTOR = 23;
    private static final int OP_TYPE_MATRIX = 24;
    private static final int OP_TYPE_IMAGE = 25;
    private static final int OP_TYPE_SAMPLER = 26;
    private static final int OP_TYPE_SAMPLED_IMAGE = 27;
    private static final int OP_TYPE_ARRAY = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY = 29;
    private static final int OP_TYPE_STRUCT = 30;
    private static final int OP_TYPE_POINTER = 32;
    private static final int OP_CONSTANT = 43;
    private static final int OP_VARIABLE = 59;
    private static final int OP_DECORATE = 71;
    private static final int OP_MEMBER_DECORATE = 72;

    // Decorations.
    private static final int DECORATION_BLOCK = 2;
    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;
    private static final int DECORATION_OFFSET = 35;
    private static final int DECORATION_ARRAY_STRIDE = 6;

    // Storage classes.
    private static final int STORAGE_UNIFORM_CONSTANT = 0;
    private static final int STORAGE_UNIFORM = 2;

    /** Execution models, from the SPIR-V specification: the stage a module is written for. */
    public static final int EXECUTION_VERTEX = 0;
    public static final int EXECUTION_FRAGMENT = 4;

    /** One sampled texture the shader reads. */
    public record Sampler(String name, int set, int binding) {}

    /** One member of a uniform block, as the compiler laid it out. */
    public record Member(String name, int offset, int size) {}

    /** One uniform block: what GL calls one of a shader's uniforms. */
    public record Block(String name, int set, int binding, List<Member> members, int size) {
        /** The offset of a member, or -1 when the block has no such member. */
        public int offsetOf(String memberName) {
            for (Member member : members) {
                if (member.name().equals(memberName)) {
                    return member.offset();
                }
            }
            return -1;
        }
    }

    /** What a module declares. */
    public record Module(List<Sampler> samplers, List<Block> blocks) {
        public Sampler sampler(String samplerName) {
            for (Sampler sampler : samplers) {
                if (sampler.name().equals(samplerName)) {
                    return sampler;
                }
            }
            return null;
        }

        public Block block(String blockName) {
            for (Block block : blocks) {
                if (block.name().equals(blockName)) {
                    return block;
                }
            }
            return null;
        }

        /** The block a named uniform lives in, or null. GL names the member, not the block, so this is the lookup. */
        public Block blockContaining(String memberName) {
            for (Block block : blocks) {
                if (block.offsetOf(memberName) >= 0) {
                    return block;
                }
            }
            return null;
        }
    }

    private SpirvReflection() {}

    /**
     * The stage a module declares, from its {@code OpEntryPoint}.
     *
     * <p>Used to reject a module that was shipped next to a shader of the other stage: a vertex shader fed a fragment
     * module compiles, links and then draws nothing, which is far harder to diagnose than refusing it here.</p>
     *
     * @return the execution model, or -1 when the module has no entry point
     */
    public static int executionModel(byte[] spirv) {
        int[] words = toWords(spirv);
        if (words.length < 5 || words[0] != MAGIC) {
            return -1;
        }
        int index = 5;
        while (index < words.length) {
            int head = words[index];
            int opcode = head & 0xFFFF;
            int count = head >>> 16;
            if (count == 0 || index + count > words.length) {
                break;
            }
            if (opcode == OP_ENTRY_POINT && count >= 3) {
                return words[index + 1];
            }
            index += count;
        }
        return -1;
    }

    /**
     * Reads a module's interface.
     *
     * @throws IllegalArgumentException when the bytes are not a SPIR-V module, which is a caller bug rather than a
     *                                  shader problem
     */
    public static Module reflect(byte[] spirv) {
        int[] words = toWords(spirv);
        if (words.length < 5 || words[0] != MAGIC) {
            throw new IllegalArgumentException("not a SPIR-V module: " + spirv.length + " bytes");
        }

        TypeTable types = new TypeTable();
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, int[]> decorations = new LinkedHashMap<>();
        Map<Integer, Map<Integer, Integer>> memberOffsets = new LinkedHashMap<>();
        Map<Integer, String> memberNames = new LinkedHashMap<>();
        Map<Integer, long[]> constants = new LinkedHashMap<>();
        List<int[]> variables = new ArrayList<>();

        int index = 5;
        while (index < words.length) {
            int head = words[index];
            int opcode = head & 0xFFFF;
            int count = head >>> 16;
            if (count == 0 || index + count > words.length) {
                // A truncated instruction means the module is malformed; stop rather than read past the end.
                break;
            }
            switch (opcode) {
                case OP_NAME -> names.put(words[index + 1], string(words, index + 2, count - 2));
                case OP_MEMBER_NAME -> memberNames.put(
                        (words[index + 1] << 8) | words[index + 2], string(words, index + 3, count - 3));
                case OP_TYPE_BOOL -> types.add(words[index + 1], new Type(TypeKind.BOOL, 4, 0));
                case OP_TYPE_INT, OP_TYPE_FLOAT -> types.add(words[index + 1],
                        new Type(opcode == OP_TYPE_INT ? TypeKind.INT : TypeKind.FLOAT,
                                words[index + 2] / 8, 0));
                case OP_TYPE_VECTOR -> types.add(words[index + 1],
                        new Type(TypeKind.VECTOR, 0, words[index + 2], words[index + 3]));
                case OP_TYPE_MATRIX -> types.add(words[index + 1],
                        new Type(TypeKind.MATRIX, 0, words[index + 2], words[index + 3]));
                case OP_TYPE_IMAGE -> types.add(words[index + 1], new Type(TypeKind.IMAGE, 0, 0));
                case OP_TYPE_SAMPLER -> types.add(words[index + 1], new Type(TypeKind.SAMPLER, 0, 0));
                case OP_TYPE_SAMPLED_IMAGE -> types.add(words[index + 1],
                        new Type(TypeKind.SAMPLED_IMAGE, 0, words[index + 2], 0));
                case OP_TYPE_ARRAY -> types.add(words[index + 1],
                        new Type(TypeKind.ARRAY, 0, words[index + 2], words[index + 3]));
                case OP_TYPE_RUNTIME_ARRAY -> types.add(words[index + 1],
                        new Type(TypeKind.ARRAY, 0, words[index + 2], 0));
                case OP_TYPE_STRUCT -> {
                    int[] members = new int[count - 2];
                    System.arraycopy(words, index + 2, members, 0, members.length);
                    types.add(words[index + 1], new Type(TypeKind.STRUCT, 0, members));
                }
                // A pointer carries both the storage class (which decides sampler vs block) and the pointee type.
                case OP_TYPE_POINTER -> types.add(words[index + 1],
                        new Type(TypeKind.POINTER, 0, words[index + 3], words[index + 2]));
                case OP_CONSTANT -> constants.put(words[index + 2], new long[] {words[index + 3] & 0xFFFFFFFFL});
                case OP_VARIABLE -> variables.add(new int[] {words[index + 1], words[index + 2]});
                case OP_DECORATE -> {
                    int target = words[index + 1];
                    int decoration = words[index + 2];
                    int value = count > 3 ? words[index + 3] : 1;
                    decorations.computeIfAbsent(target, k -> new int[64])[decoration] = value + 1;
                }
                case OP_MEMBER_DECORATE -> {
                    int key = (words[index + 1] << 8) | words[index + 2];
                    int decoration = words[index + 3];
                    int value = words[index + 4];
                    memberOffsets.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(decoration, value);
                }
                default -> {
                }
            }
            index += count;
        }

        List<Sampler> samplers = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (int[] variable : variables) {
            int pointerType = variable[0];
            int id = variable[1];
            Type pointer = types.get(pointerType);
            if (pointer == null || pointer.kind != TypeKind.POINTER) {
                continue;
            }
            int storage = pointer.arrayStrideOrMemberCount;   // the pointer's storage class, per Type's comment
            int pointee = pointer.memberOrComponent;
            // A sampler pairs an image with a sampler state; a buffer is a struct.
            Type resolved = types.resolve(pointee);
            int[] decor = decorations.getOrDefault(id, new int[64]);
            int set = Math.max(0, decor[DECORATION_DESCRIPTOR_SET] - 1);
            int binding = Math.max(0, decor[DECORATION_BINDING] - 1);

            if (storage == STORAGE_UNIFORM_CONSTANT) {
                samplers.add(new Sampler(nameOf(names, id, "sampler" + binding), set, binding));
            } else if (storage == STORAGE_UNIFORM && resolved != null && resolved.kind == TypeKind.STRUCT) {
                boolean isBlock = hasDecoration(decor, DECORATION_BLOCK);
                List<Member> members = new ArrayList<>();
                int blockSize = 0;
                for (int i = 0; i < resolved.memberTypes.length; i++) {
                    int memberId = resolved.memberTypes[i];
                    Map<Integer, Integer> offsets = memberOffsets.getOrDefault(
                            (resolved.declarationId << 8) | i, Map.of());
                    int offset = offsets.getOrDefault(DECORATION_OFFSET, 0);
                    int size = types.sizeOf(memberId, constants);
                    members.add(new Member(memberNameOf(memberNames, resolved, i,
                            nameOf(names, memberId, "member" + i)), offset, size));
                    blockSize = Math.max(blockSize, offset + size);
                }
                if (isBlock || !members.isEmpty()) {
                    blocks.add(new Block(nameOf(names, id, "uniforms"), set, binding, members, blockSize));
                }
            }
        }
        return new Module(List.copyOf(samplers), List.copyOf(blocks));
    }

    private static boolean hasDecoration(int[] decorations, int decoration) {
        return decoration < decorations.length && decorations[decoration] != 0;
    }

    private static String nameOf(Map<Integer, String> names, int id, String fallback) {
        String name = names.get(id);
        return name == null || name.isEmpty() ? fallback : name;
    }

    private static String memberNameOf(Map<Integer, String> memberNames, Type struct, int memberIndex,
                                       String fallback) {
        String name = memberNames.get((struct.declarationId << 8) | memberIndex);
        return name == null || name.isEmpty() ? fallback : name;
    }

    private static int[] toWords(byte[] bytes) {
        int[] words = new int[bytes.length / 4];
        for (int i = 0; i < words.length; i++) {
            int at = i * 4;
            words[i] = (bytes[at] & 0xFF) | ((bytes[at + 1] & 0xFF) << 8)
                    | ((bytes[at + 2] & 0xFF) << 16) | ((bytes[at + 3] & 0xFF) << 24);
        }
        return words;
    }

    /** A null-terminated UTF-8 string packed into consecutive words. */
    private static String string(int[] words, int from, int wordCount) {
        byte[] bytes = new byte[wordCount * 4];
        for (int i = 0; i < wordCount; i++) {
            int word = words[from + i];
            bytes[i * 4] = (byte) word;
            bytes[i * 4 + 1] = (byte) (word >>> 8);
            bytes[i * 4 + 2] = (byte) (word >>> 16);
            bytes[i * 4 + 3] = (byte) (word >>> 24);
        }
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) {
            end++;
        }
        return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
    }

    private enum TypeKind { BOOL, INT, FLOAT, VECTOR, MATRIX, IMAGE, SAMPLER, SAMPLED_IMAGE, ARRAY, STRUCT, POINTER }

    /**
     * One type declaration.
     *
     * <p>The field names are deliberately generic: a vector needs a component type and a count, an array an element
     * type and a length, a pointer a storage class and a pointee. Spelling them apart would mean a hierarchy for four
     * integers.</p>
     */
    private static final class Type {
        final TypeKind kind;
        final int scalarSize;
        final int memberOrComponent;
        final int arrayStrideOrMemberCount;
        final int[] memberTypes;
        int declarationId;

        Type(TypeKind kind, int scalarSize, int memberOrComponent) {
            this(kind, scalarSize, memberOrComponent, 0);
        }

        Type(TypeKind kind, int scalarSize, int memberOrComponent, int arrayStrideOrMemberCount) {
            this.kind = kind;
            this.scalarSize = scalarSize;
            this.memberOrComponent = memberOrComponent;
            this.arrayStrideOrMemberCount = arrayStrideOrMemberCount;
            this.memberTypes = null;
        }

        Type(TypeKind kind, int scalarSize, int[] memberTypes) {
            this.kind = kind;
            this.scalarSize = scalarSize;
            this.memberOrComponent = 0;
            this.arrayStrideOrMemberCount = 0;
            this.memberTypes = memberTypes;
        }
    }

    private static final class TypeTable {
        private final Map<Integer, Type> byId = new LinkedHashMap<>();

        void add(int id, Type type) {
            type.declarationId = id;
            byId.put(id, type);
        }

        Type get(int id) {
            return byId.get(id);
        }

        /** Walks through pointers and sampled-image wrappers to the type that describes the data. */
        Type resolve(int id) {
            Type type = byId.get(id);
            int guard = 0;
            while (type != null && guard++ < 8) {
                if (type.kind == TypeKind.POINTER) {
                    type = byId.get(type.memberOrComponent);
                } else if (type.kind == TypeKind.SAMPLED_IMAGE) {
                    type = byId.get(type.memberOrComponent);
                } else {
                    return type;
                }
            }
            return type;
        }

        /** The declared size of a type as a block member, in bytes. Offsets come from the module, so no padding. */
        int sizeOf(int id, Map<Integer, long[]> constants) {
            Type type = byId.get(id);
            if (type == null) {
                return 0;
            }
            return switch (type.kind) {
                case BOOL, INT, FLOAT -> type.scalarSize;
                case VECTOR -> sizeOf(type.memberOrComponent, constants) * type.arrayStrideOrMemberCount;
                case MATRIX -> sizeOf(type.memberOrComponent, constants) * type.arrayStrideOrMemberCount;
                case ARRAY -> {
                    long[] length = constants.get(type.arrayStrideOrMemberCount);
                    long count = length == null ? 0 : length[0];
                    yield (int) (sizeOf(type.memberOrComponent, constants) * count);
                }
                case STRUCT -> {
                    int size = 0;
                    if (type.memberTypes != null) {
                        for (int member : type.memberTypes) {
                            size += sizeOf(member, constants);
                        }
                    }
                    yield size;
                }
                default -> 0;
            };
        }
    }
}
