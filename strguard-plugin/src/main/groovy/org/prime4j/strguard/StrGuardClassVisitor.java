package org.prime4j.strguard;

import org.objectweb.asm.*;
import org.prime4j.strguard.api.IStrGuard;
import org.prime4j.strguard.api.IkeyGenerator;

import java.util.*;

public class StrGuardClassVisitor extends ClassVisitor {

    private static final String IGNORE_ANNOTATION = "Lorg/prime4j/strguard/annotation/NotStrGuard;";
    private boolean isClInitExists;
    private final List<ClassStringField> mStaticFinalFields = new ArrayList<>();
    private final List<ClassStringField> mStaticFields = new ArrayList<>();
    private final List<ClassStringField> mFinalFields = new ArrayList<>();
    private final Map<String, byte[]> keyCache = new HashMap<>();
    private final Map<String, byte[]> valueCache = new HashMap<>();
    private final IStrGuard myStrGuard;
    private final List<String> myLogs;
    private final IkeyGenerator myKeyGenerator;
    private String myClassName;
    private final InstructionWriter myInstructionWriter;

    private boolean mIgnoreClass;
    private final boolean mKeepMetadata;

    public StrGuardClassVisitor(IStrGuard strGuard, List<String> logs, String strGuardClassName, ClassVisitor cv, IkeyGenerator kg, boolean keepMetadata) {
        super(Opcodes.ASM9, cv);
        this.myStrGuard = strGuard;
        this.myLogs = logs;
        this.myKeyGenerator = kg;
        this.mKeepMetadata = keepMetadata;
        strGuardClassName = strGuardClassName.replace('.', '/');
        this.myInstructionWriter = new ByteArrayInstructionWriter(strGuardClassName);
    }


    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.myClassName = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        if (desc.equals("Lkotlin/Metadata;") && !mKeepMetadata) {
            mIgnoreClass = false;
            return null;
        }
        mIgnoreClass = IGNORE_ANNOTATION.equals(desc);
        return super.visitAnnotation(desc, visible);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if (ClassStringField.STRING_DESC.equals(desc) && name != null && !mIgnoreClass) {
            // static final, in this condition, the value is null or not null.
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mStaticFinalFields.add(new ClassStringField(name, (String) value));
                value = null;
            }
            // static, in this condition, the value is null.
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) == 0) {
                mStaticFields.add(new ClassStringField(name, (String) value));
                value = null;
            }

            // final, in this condition, the value is null or not null.
            if ((access & Opcodes.ACC_STATIC) == 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mFinalFields.add(new ClassStringField(name, (String) value));
                value = null;
            }
        }
        return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
        if (mv == null || mIgnoreClass) {
            return mv;
        }
        if ("<clinit>".equals(name)) {
            isClInitExists = true;
            // If clinit exists meaning the static fields (not final) would have be inited here.
            mv = new MethodVisitor(Opcodes.ASM9, mv) {

                private String lastStashCst;

                @Override
                public void visitCode() {
                    super.visitCode();
                    // Here init static final fields.
                    for (ClassStringField field : mStaticFinalFields) {
                        if (!canEncrypted(field.value)) {
                            continue;
                        }
                        encryptAndWrite(field.value, mv);
                        super.visitFieldInsn(Opcodes.PUTSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
                    }
                }

                @Override
                public void visitLdcInsn(Object cst) {
                    // Here init static or static final fields, but we must check field name int 'visitFieldInsn'
                    if (cst instanceof String && canEncrypted((String) cst)) {
                        lastStashCst = (String) cst;
                        encryptAndWrite(lastStashCst, mv);
                    } else {
                        lastStashCst = null;
                        super.visitLdcInsn(cst);
                    }
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                    if (myClassName.equals(owner) && lastStashCst != null) {
                        boolean isContain = false;
                        for (ClassStringField field : mStaticFields) {
                            if (field.name.equals(name)) {
                                isContain = true;
                                break;
                            }
                        }
                        if (!isContain) {
                            for (ClassStringField field : mStaticFinalFields) {
                                if (field.name.equals(name) && field.value == null) {
                                    field.value = lastStashCst;
                                    break;
                                }
                            }
                        }
                    }
                    lastStashCst = null;
                    super.visitFieldInsn(opcode, owner, name, desc);
                }
            };

        } else if ("<init>".equals(name)) {
            // Here init final(not static) and normal fields
            mv = new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitLdcInsn(Object cst) {
                    // We don't care about whether the field is final or normal
                    if (cst instanceof String && canEncrypted((String) cst)) {
                        encryptAndWrite((String) cst, mv);
                    } else {
                        super.visitLdcInsn(cst);
                    }
                }
            };
        } else {
            mv = new MethodVisitor(Opcodes.ASM9, mv) {

                @Override
                public void visitLdcInsn(Object cst) {
                    // 处理字符串字面量
                    if (cst instanceof String strValue) {

                        // 检查是否可以加密
                        if (canEncrypted(strValue)) {
                            // 如果是静态final字段
                            for (ClassStringField field : mStaticFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitFieldInsn(Opcodes.GETSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            // 如果是final字段(非静态)
                            for (ClassStringField field : mFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            // 加密并写入
                            encryptAndWrite(strValue, mv);
                            return;
                        }
                    }

                    // 不需要加密,调用原始方法
                    super.visitLdcInsn(cst);
                }
            };
        }
        return mv;
    }


    @Override
    public void visitEnd() {
        if (!mIgnoreClass && !isClInitExists && !mStaticFinalFields.isEmpty()) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
            // Here init static final fields.
            for (ClassStringField field : mStaticFinalFields) {
                if (!canEncrypted(field.value)) {
                    continue;
                }
                encryptAndWrite(field.value, mv);
                mv.visitFieldInsn(Opcodes.PUTSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
            }
            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(1, 0);
            mv.visitEnd();
        }
        super.visitEnd();
    }

    private boolean canEncrypted(String value) {
        if (value == null || value.isEmpty() || value.trim().isEmpty()) {
            return false;
        }

        if (isSpecialString(value)) {
            return false;
        }

        return value.length() < 65536 >> 2 && myStrGuard.apply(value);
    }

    private boolean isSpecialString(String value) {
        // 更全面地排除编译器生成的特殊字符串
        return value.contains("$")
               || value.contains("lambda")
               || value.startsWith("access$")
               || value.contains("synthetic")
               || isJvmInternalString(value);
    }

    private boolean isJvmInternalString(String value) {
        return value.startsWith("java/")
               || value.startsWith("kotlin/")
               || value.startsWith("org/")
               || value.startsWith("sun/")
               || value.startsWith("jdk/");
    }

    private String encryptAndWrite(String value, MethodVisitor mv) {
        byte[] key = getOrGenerateKey(value);
        byte[] encryptValue = getOrGenerateValue(value, key);
        String result = myInstructionWriter.write(key, encryptValue, mv);
        myLogs.add(value + " -> " + result);
        return result;
    }

    private byte[] getOrGenerateKey(String value) {
        return keyCache.computeIfAbsent(value, myKeyGenerator::generate);
    }

    private byte[] getOrGenerateValue(String value, byte[] key) {
        return valueCache.computeIfAbsent(value, s -> myStrGuard.encode(s, key));
    }

    private static abstract class InstructionWriter {

        public final String mFogClassName;

        InstructionWriter(String fogClassName) {
            mFogClassName = fogClassName;
        }

        abstract String write(byte[] key, byte[] value, MethodVisitor mv);

        protected void writeClass(MethodVisitor mv, String descriptor) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, mFogClassName, "decode", descriptor, false);
        }

    }

    private static class ByteArrayInstructionWriter extends InstructionWriter {

        private ByteArrayInstructionWriter(String fogClassName) {
            super(fogClassName);
        }

        @Override
        String write(byte[] key, byte[] value, MethodVisitor mv) {
            pushArray(mv, value);
            pushArray(mv, key);
            super.writeClass(mv, "([B[B)Ljava/lang/String;");
            return Arrays.toString(value);
        }

        private void pushArray(MethodVisitor mv, byte[] buffer) {
            pushNumber(mv, buffer.length);
            mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_BYTE);
            mv.visitInsn(Opcodes.DUP);
            for (int i = 0; i < buffer.length; i++) {
                pushNumber(mv, i);
                pushNumber(mv, buffer[i]);
                mv.visitInsn(Type.BYTE_TYPE.getOpcode(Opcodes.IASTORE));
                if (i < buffer.length - 1) mv.visitInsn(Opcodes.DUP);
            }
        }

        private void pushNumber(MethodVisitor mv, final int value) {
            if (value >= -1 && value <= 5) {
                mv.visitInsn(Opcodes.ICONST_0 + value);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.BIPUSH, value);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                mv.visitIntInsn(Opcodes.SIPUSH, value);
            } else {
                mv.visitLdcInsn(value);
            }
        }

    }


}
