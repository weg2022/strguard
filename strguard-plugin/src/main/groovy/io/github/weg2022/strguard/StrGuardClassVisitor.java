package io.github.weg2022.strguard;

import org.objectweb.asm.*;
import io.github.weg2022.strguard.api.IStrGuard;
import io.github.weg2022.strguard.api.IkeyGenerator;

import java.util.*;

public class StrGuardClassVisitor extends ClassVisitor {

    private static final String KEEP_STRING_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepString;";
    private static final String KEEP_METADATA_ANNOTATION = "Lio/github/weg2022/strguard/annotation/KeepMetadata;";
    private boolean isClInitExists;
    private final List<ClassStringField> mStaticFinalFields = new ArrayList<>();
    private final List<ClassStringField> mStaticFields = new ArrayList<>();
    private final List<ClassStringField> mFinalFields = new ArrayList<>();
    private final Map<String, byte[]> keyCache = new HashMap<>();
    private final Map<String, byte[]> valueCache = new HashMap<>();
    private final IStrGuard myStrGuard;
    private final List<String> mStrGuardLog;
    private final List<String> mMetadataLog;
    private final IkeyGenerator myKeyGenerator;
    private String myClassName;
    private final InstructionWriter myInstructionWriter;

    private boolean mKeepStringClass = false;
    private boolean mKeepMetadataClass = false;
    private final StrGuardExtension extension;

    private final String className;

    public StrGuardClassVisitor(IStrGuard strGuard, List<String> strLog, List<String> metadataLog,
                                String strGuardClassName, ClassVisitor cv, IkeyGenerator kg,
                                StrGuardExtension extension, String className
    ) {
        super(Opcodes.ASM9, cv);
        this.myStrGuard = strGuard;
        this.mStrGuardLog = strLog;
        this.mMetadataLog = metadataLog;
        this.myKeyGenerator = kg;
        this.extension = extension;
        this.className = className;
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
        if (KEEP_STRING_ANNOTATION.equals(desc))
            mKeepStringClass = true;
        if (KEEP_METADATA_ANNOTATION.equals(desc))
            mKeepMetadataClass = true;

        if (isMetadata(desc) && !mKeepMetadataClass) {
            if (extension.isRemoveMetadata() && isRemoveMetadata(className, extension.getRemoveMetadataPackages(), extension.getKeepMetadataPackages())) {
                mMetadataLog.add(desc + " -> null");
                if (extension.isConsoleOutput())
                    System.out.println("StrGuard[REMOVE_METADATA]: " + className + " @" + desc);
                return null;
            } else {
                mMetadataLog.add(desc + " -> " + desc);
            }
        }
        return super.visitAnnotation(desc, visible);
    }

    private boolean isMetadata(String desc) {
        if (desc == null || desc.trim().isEmpty()) {
            return false;
        }
        return desc.equals("Lkotlin/Metadata;")
                || desc.equals("Lkotlin/coroutines/jvm/internal/DebugMetadata;")
                || desc.equals("Lkotlin/jvm/internal/SourceDebugExtension;");
    }

    private static final String[] whiteList = new String[]{
            "io.github.weg2022"
    };

    private boolean isRemoveMetadata(String classname, String[] removeMetadataPackages, String[] keepMetadataClasses) {
        if (classname == null || classname.trim().isEmpty()) {
            return false;
        }
        for (String name : whiteList) {
            if (classname.replace('/', '.').startsWith(name + ".")) {
                return false;
            }
        }

        if (keepMetadataClasses != null) {
            for (String name : keepMetadataClasses) {
                if (classname.replace('/', '.').startsWith(name + ".")) {
                    return false;
                }
            }
        }

        if (removeMetadataPackages != null) {
            for (String name : removeMetadataPackages) {
                if (classname.replace('/', '.').startsWith(name + ".")) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
        if (ClassStringField.STRING_DESC.equals(desc) && name != null && !mKeepStringClass) {
            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) {
                mStaticFinalFields.add(new ClassStringField(name, (String) value));
                value = null;
            }

            if ((access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) == 0) {
                mStaticFields.add(new ClassStringField(name, (String) value));
                value = null;
            }


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
        if (mv == null || mKeepStringClass) {
            return mv;
        }
        if ("<clinit>".equals(name)) {
            isClInitExists = true;
            mv = new MethodVisitor(Opcodes.ASM9, mv) {

                private String lastStashCst;

                @Override
                public void visitCode() {
                    super.visitCode();
                    for (var field : mStaticFinalFields) {
                        if (!canEncrypted(field.value)) {
                            continue;
                        }
                        encryptAndWrite(field.value, mv);
                        super.visitFieldInsn(Opcodes.PUTSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
                    }
                }

                @Override
                public void visitLdcInsn(Object cst) {
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
            mv = new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitLdcInsn(Object cst) {
                    if (cst instanceof String && canEncrypted((String) cst)) {
                        encryptAndWrite((String) cst, mv);
                    } else {
                        super.visitLdcInsn(cst);
                    }
                }
            };
        } else {
            mv = new MethodVisitor(Opcodes.ASM9, mv) {
                private int dynamicArgCounter;
                private int staticArgIndex;

                @Override
                public void visitLdcInsn(Object cst) {
                    if (cst instanceof String strValue) {

                        if (canEncrypted(strValue)) {
                            for (var field : mStaticFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitFieldInsn(Opcodes.GETSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            for (var field : mFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            encryptAndWrite(strValue, mv);
                            return;
                        }
                    }

                    super.visitLdcInsn(cst);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                    this.dynamicArgCounter = 0;
                    this.staticArgIndex = 0;

                    if (extension.isV9StringConcatEnabled() && "makeConcatWithConstants".equals(bsm.getName()) &&
                            "java/lang/invoke/StringConcatFactory".equals(bsm.getOwner()) &&
                            bsm.getTag() == Opcodes.H_INVOKESTATIC) {

                        var recipe = (String) bsmArgs[0];
                        var staticConcatArgs = Arrays.copyOfRange(bsmArgs, 1, bsmArgs.length);

                        var invokeDynamicMethodType = Type.getMethodType(descriptor);
                        var dynamicArgTypes = invokeDynamicMethodType.getArgumentTypes();

                        int currentFreeLocal = 256;
                        int[] dynamicArgLocalVarIndices = new int[dynamicArgTypes.length];

                        for (int i = 0; i < dynamicArgTypes.length; i++) {
                            dynamicArgLocalVarIndices[i] = currentFreeLocal;
                            currentFreeLocal += dynamicArgTypes[i].getSize();
                        }

                        int sbLocalVarIndex = currentFreeLocal;
                        for (int i = dynamicArgTypes.length - 1; i >= 0; i--) {
                            var argType = dynamicArgTypes[i];
                            super.visitVarInsn(argType.getOpcode(Opcodes.ISTORE), dynamicArgLocalVarIndices[i]);
                        }

                        super.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                        super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                        super.visitVarInsn(Opcodes.ASTORE, sbLocalVarIndex);

                        var currentPart = new StringBuilder();

                        for (char c : recipe.toCharArray()) {
                            if (c == '\u0001') { // Dynamic argument placeholder
                                if (!currentPart.isEmpty()) {
                                    var constantString = currentPart.toString();
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);
                                    if (canEncrypted(constantString)) {
                                        encryptAndWrite(constantString, this);
                                    } else {
                                        super.visitLdcInsn(constantString);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                                    super.visitInsn(Opcodes.POP);
                                    currentPart.setLength(0);
                                }

                                if (dynamicArgCounter < dynamicArgTypes.length) {
                                    var argType = dynamicArgTypes[dynamicArgCounter];
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);
                                    super.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), dynamicArgLocalVarIndices[dynamicArgCounter]);
                                    dynamicArgCounter++;

                                    String appendDesc;
                                    if (argType.getSort() == Type.OBJECT || argType.getSort() == Type.ARRAY) {
                                        appendDesc = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else {
                                        appendDesc = "(" + argType.getDescriptor() + ")Ljava/lang/StringBuilder;";
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc, false);
                                    super.visitInsn(Opcodes.POP);
                                } else {
                                    System.err.println("StringConcatFactory recipe: dynamic argument mismatch!");
                                }
                            } else if (c == '\u0002') { // Static argument placeholder
                                if (!currentPart.isEmpty()) {
                                    var constantString = currentPart.toString();
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);
                                    if (canEncrypted(constantString)) {
                                        encryptAndWrite(constantString, this);
                                    } else {
                                        super.visitLdcInsn(constantString);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                                    super.visitInsn(Opcodes.POP);
                                    currentPart.setLength(0);
                                }

                                if (staticArgIndex < staticConcatArgs.length) {
                                    var staticArg = staticConcatArgs[staticArgIndex++];
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);

                                    String appendDescForStatic;
                                    if (staticArg instanceof String s) {
                                        if (canEncrypted(s)) {
                                            encryptAndWrite(s, this);
                                        } else {
                                            super.visitLdcInsn(s);
                                        }
                                        appendDescForStatic = "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Integer) {
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(I)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Long) {
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(J)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Float) {
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(F)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Double) {
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(D)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Type type) {
                                        super.visitLdcInsn(type);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Handle handle) {
                                        super.visitLdcInsn(handle);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else {
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDescForStatic, false);
                                    super.visitInsn(Opcodes.POP);
                                } else {
                                    System.err.println("StringConcatFactory recipe: static argument mismatch!");
                                }
                            } else {
                                currentPart.append(c);
                            }
                        }

                        if (!currentPart.isEmpty()) {
                            String constantString = currentPart.toString();
                            super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);
                            if (canEncrypted(constantString)) {
                                encryptAndWrite(constantString, this);
                            } else {
                                super.visitLdcInsn(constantString);
                            }
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                            super.visitInsn(Opcodes.POP);
                        }

                        super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                    } else {
                        super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
                    }

                }
            };
        }
        return mv;
    }


    @Override
    public void visitEnd() {
        if (!mKeepStringClass && !isClInitExists && !mStaticFinalFields.isEmpty()) {
            MethodVisitor mv = super.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            mv.visitCode();
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

        return value.length() < (65536 >> 2) && myStrGuard.apply(value);
    }


    private boolean isSpecialString(String value) {
        return value.contains("lambda")
                || value.startsWith("access$")
                || value.contains("synthetic")
                || isJvmInternalString(value)
                // Common bytecode-related string patterns (method/field/array descriptors, primitive types)
                || value.startsWith("(") && value.contains(";") && value.contains("/") // Likely a method descriptor (e.g., "(Ljava/lang/String;)V")
                || (value.startsWith("L") && value.endsWith(";") && value.contains("/")) // Likely an object type descriptor (e.g., "Ljava/lang/Object;")
                || value.startsWith("[") && value.endsWith(";") && value.contains("/")// Likely an array type descriptor (e.g., "[Ljava/lang/String;")
                || (value.length() == 1 && "ZBCDFIJSV".contains(value)) // Primitive type descriptors (e.g., "I", "Z", "V" for void)
                ;
    }

    private boolean isJvmInternalString(String value) {
        return value.startsWith("java/")
                || value.startsWith("kotlin/")
                || value.startsWith("org/")
                || value.startsWith("sun/")
                || value.startsWith("jdk/");
    }


    private void encryptAndWrite(String value, MethodVisitor mv) {
        byte[] key = getOrGenerateKey(value);
        byte[] encryptValue = getOrGenerateValue(value, key);
        String result = myInstructionWriter.write(key, encryptValue, mv);
        mStrGuardLog.add(value + " -> " + result);
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

        protected void writeClass(MethodVisitor mv) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, mFogClassName, "decode", "([B[B)Ljava/lang/String;", false);
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
            super.writeClass(mv);
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
