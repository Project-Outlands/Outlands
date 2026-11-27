/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.discovery.asm;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.discovery.ASMDataTable;
import net.minecraftforge.fml.common.discovery.ModCandidate;
import net.minecraftforge.fml.common.discovery.cache.ClassDetail;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.MemberDetail;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ASMModParser
{
    private Type asmType;
    private int classVersion;
    private int classAccess;
    private Type asmSuperType;
    private final LinkedList<ModAnnotation> annotations = Lists.newLinkedList();
    private final Set<String> interfaces = Sets.newHashSet();
    /** The members as the class file declares them; the parse already walks them, keeping them is what costs. */
    private final List<MemberRecord> members = Lists.newArrayList();
    /** The detail of the members that have one, keyed by {@link MemberRecord#key}; sparse on purpose. */
    private final java.util.Map<String, MemberDetail> memberDetails = com.google.common.collect.Maps.newHashMap();
    private String classSignature;
    private ClassDetail.Inner innerSelf;
    private ClassDetail.Enclosing enclosing;
    private String sourceFile;

    public enum AnnotationType
    {
        CLASS, FIELD, METHOD, SUBTYPE;
    }

    /**
     * @param bytes the class file, read by a caller that already knew how large it is
     *
     * <p>Reading through an {@link InputStream} makes ASM copy the class into a growing buffer and then copy it again
     * to hand back the bytes; a caller holding a jar entry knows the length from the central directory, so it can hand
     * the bytes over instead. Measured over a start: 313 MB of allocation inside this constructor, most of it those
     * two copies.
     */
    public ASMModParser(byte[] bytes) throws IOException
    {
        this(new ClassReader(bytes));
    }

    public ASMModParser(InputStream stream) throws IOException
    {
        this(new ClassReader(stream));
    }

    private ASMModParser(ClassReader reader) throws IOException
    {
        try
        {
            // SKIP_DEBUG is deliberately not set: the SourceFile attribute is a debug attribute, and a language
            // server wants to name the file a class came from. Method bodies are still skipped, which is where the
            // line and variable tables (the expensive part of debug info) live.
            reader.accept(new ModClassVisitor(this), ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        }
        catch (Exception ex)
        {
            FMLLog.log.error("Unable to read a class file correctly", ex);
            throw new LoaderException(ex);
        }
    }

    /** Records one member of the class being parsed, and the detail a language server asks for when there is some. */
    public void addMember(String name, String descriptor, String signature, String[] exceptions, Object value, int access, boolean method)
    {
        members.add(new MemberRecord(name, descriptor, access, method));
        final MemberDetail detail = MemberDetail.of(signature, exceptions, value);
        if (detail != null)
        {
            memberDetails.put(MemberRecord.key(name, descriptor, method), detail);
        }
    }

    /** @return the detail of the class body that was visited last, which is only meaningful once the visit is over */
    public ClassDetail toDetail()
    {
        if (classSignature == null && innerSelf == null && enclosing == null && sourceFile == null && memberDetails.isEmpty())
        {
            return ClassDetail.EMPTY;
        }
        return new ClassDetail(classSignature, innerSelf, enclosing, sourceFile,
                memberDetails.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(memberDetails));
    }

    /** Sets the {@code SourceFile} attribute of the class being parsed. */
    public void sourceFile(String source)
    {
        this.sourceFile = source;
    }

    /** Sets the {@code EnclosingMethod} attribute, which local and anonymous classes carry. */
    public void enclosingMethod(String owner, String name, String descriptor)
    {
        this.enclosing = new ClassDetail.Enclosing(owner, name, descriptor);
    }

    /** Keeps this class' own entry of the {@code InnerClasses} attribute; the other entries describe other classes. */
    public void innerClass(String name, String outerName, String innerName, int access)
    {
        if (asmType != null && name.equals(asmType.getInternalName()))
        {
            this.innerSelf = new ClassDetail.Inner(outerName, innerName, access);
        }
    }

    /** @return the internal name of the class, once its header has been visited */
    public String internalName()
    {
        return asmType.getInternalName();
    }

    /** @return the members this parse saw */
    public MemberRecord[] toMembers()
    {
        return members.toArray(new MemberRecord[0]);
    }

    public void beginNewTypeName(String typeQName, int classVersion, int classAccess, String typeSignature, String superClassQName, String[] interfaces)
    {
        this.classSignature = typeSignature;
        this.asmType = Type.getObjectType(typeQName);
        this.classVersion = classVersion;
        this.classAccess = classAccess;
        this.asmSuperType = !Strings.isNullOrEmpty(superClassQName) ? Type.getObjectType(superClassQName) : null;
        this.interfaces.addAll(Arrays.asList(interfaces));
    }

    public void startClassAnnotation(String annotationName)
    {
        ModAnnotation ann = new ModAnnotation(AnnotationType.CLASS, Type.getType(annotationName), this.asmType.getClassName());
        annotations.addFirst(ann);
    }

    public void addAnnotationProperty(String key, Object value)
    {
        annotations.getFirst().addProperty(key, value);
    }

    public void startFieldAnnotation(String fieldName, String annotationName)
    {
        ModAnnotation ann = new ModAnnotation(AnnotationType.FIELD, Type.getType(annotationName), fieldName);
        annotations.addFirst(ann);
    }

    @Override
    public String toString()
    {
        return MoreObjects.toStringHelper("ASMAnnotationDiscoverer")
                .add("className", asmType.getClassName())
                .add("classVersion", classVersion)
                .add("superName", asmSuperType.getClassName())
                .add("annotations", annotations)
                .toString();
    }

    public Type getASMType()
    {
        return asmType;
    }

    public int getClassVersion()
    {
        return classVersion;
    }

    public Type getASMSuperType()
    {
        return asmSuperType;
    }

    public LinkedList<ModAnnotation> getAnnotations()
    {
        return annotations;
    }

    public void validate()
    {

    }

    public void sendToTable(ASMDataTable table, ModCandidate candidate)
    {
        for (ModAnnotation ma : annotations)
        {
            table.addASMData(candidate, ma.asmType.getClassName(), this.asmType.getClassName(), ma.member, ma.values);
        }

        for (String intf : interfaces)
        {
            table.addASMData(candidate, intf, this.asmType.getInternalName(), null, null);
        }
    }

    public void addAnnotationArray(String name)
    {
        annotations.getFirst().addArray(name);
    }

    public void addAnnotationEnumProperty(String name, String desc, String value)
    {
        annotations.getFirst().addEnumProperty(name, desc, value);

    }

    public void endArray()
    {
        annotations.getFirst().endArray();

    }

    public void addSubAnnotation(String name, String desc)
    {
        ModAnnotation ma = annotations.getFirst();
        annotations.addFirst(ma.addChildAnnotation(name, desc));
    }

    public void endSubAnnotation()
    {
        // take the child and stick it at the end
        ModAnnotation child = annotations.removeFirst();
        annotations.addLast(child);
    }

    public void startMethodAnnotation(String methodName, String methodDescriptor, String annotationName)
    {
        ModAnnotation ann = new ModAnnotation(AnnotationType.METHOD, Type.getType(annotationName), methodName+methodDescriptor);
        annotations.addFirst(ann);
    }

    /**
     * Immutable summary of this parse.
     */
    public ClassScanRecord toRecord()
    {
        ClassScanRecord.Annotation[] collected = new ClassScanRecord.Annotation[annotations.size()];
        int index = 0;
        for (ModAnnotation annotation : annotations)
        {
            collected[index++] = new ClassScanRecord.Annotation(
                    annotation.getASMType().getClassName(),
                    annotation.getMember(),
                    annotation.getValues());
        }
        return new ClassScanRecord(
                asmType.getInternalName(),
                classVersion,
                asmSuperType == null ? null : asmSuperType.getInternalName(),
                classAccess,
                interfaces.toArray(new String[0]),
                collected);
    }
}
