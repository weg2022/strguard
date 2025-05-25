package org.prime4j.strguard;

import org.objectweb.asm.*;
import org.prime4j.strguard.api.IStrGuard;
import org.prime4j.strguard.api.IkeyGenerator;

import java.util.*;

public class StrGuardClassVisitor extends ClassVisitor {

    private static final String KEEP_STRING_ANNOTATION = "Lorg/prime4j/strguard/annotation/KeepString;";
    private static final String KEEP_METADATA_ANNOTATION = "Lorg/prime4j/strguard/annotation/KeepMetadata;";
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
                || desc.equals("Lkotlin/jvm/internal/SourceDebugExtension;")
                ;
    }

    private static final String[] whiteList = new String[]{
            "org.prime4j.strguard"
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
        if (mv == null || mKeepStringClass) {
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
            // General method visitor to handle LDC and INVOKEDYNAMIC instructions
            // Capture for use in anonymous class
            final String currentMethodDesc = desc;
            final int currentMethodAccess = access;

            mv = new MethodVisitor(Opcodes.ASM9, mv) {
                // Declare dynamicArgCounter and staticArgIndex as fields of this anonymous class
                private int dynamicArgCounter;
                private int staticArgIndex;

                @Override
                public void visitLdcInsn(Object cst) {
                    // Process string literals
                    if (cst instanceof String strValue) {

                        // Check if it can be encrypted
                        if (canEncrypted(strValue)) {
                            // If it's a static final field
                            for (ClassStringField field : mStaticFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitFieldInsn(Opcodes.GETSTATIC, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            // If it's a final field (non-static)
                            for (ClassStringField field : mFinalFields) {
                                if (strValue.equals(field.value)) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitFieldInsn(Opcodes.GETFIELD, myClassName, field.name, ClassStringField.STRING_DESC);
                                    return;
                                }
                            }

                            // Encrypt and write
                            encryptAndWrite(strValue, mv);
                            return;
                        }
                    }

                    // No encryption needed, call original method
                    super.visitLdcInsn(cst);
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... bsmArgs) {
                    // Initialize the counters for this specific INVOKEDYNAMIC instruction
                    this.dynamicArgCounter = 0;
                    this.staticArgIndex = 0;

                    // Check if it's java/lang/invoke/StringConcatFactory.makeConcatWithConstants
                    if (extension.isV9StringConcatEnabled() && "makeConcatWithConstants".equals(bsm.getName()) &&
                            "java/lang/invoke/StringConcatFactory".equals(bsm.getOwner()) &&
                            bsm.getTag() == Opcodes.H_INVOKESTATIC) {

                        // Get the concatenation recipe string
                        String recipe = (String) bsmArgs[0];
                        // Get static arguments of makeConcatWithConstants (excluding the recipe string)
                        Object[] staticConcatArgs = Arrays.copyOfRange(bsmArgs, 1, bsmArgs.length);

                        // Get the types of dynamic arguments (from the INVOKEDYNAMIC instruction's descriptor)
                        Type invokeDynamicMethodType = Type.getMethodType(descriptor);
                        Type[] dynamicArgTypes = invokeDynamicMethodType.getArgumentTypes();

                        // --- Local Variable Management for StringBuilder and Dynamic Arguments ---
                        // Start allocating new local variables from a high index to avoid conflicts with existing variables.
                        // This is a heuristic to bypass VerifyError when COMPUTE_MAXS might not be sufficient
                        // for complex local variable type re-verification.
                        // A value like 256 is chosen to be safely beyond typical method parameters and local variables.
                        int currentFreeLocal = 256;

                        // Calculate the local variable indices for all dynamic arguments.
                        // These will be assigned contiguously starting from currentFreeLocal.
                        int[] dynamicArgLocalVarIndices = new int[dynamicArgTypes.length];
                        for (int i = 0; i < dynamicArgTypes.length; i++) {
                            dynamicArgLocalVarIndices[i] = currentFreeLocal;
                            currentFreeLocal += dynamicArgTypes[i].getSize();
                        }

                        // The StringBuilder will be stored after all dynamic arguments.
                        int sbLocalVarIndex = currentFreeLocal;
                        // No need to increment currentFreeLocal here, as its value is not used after this point.
                        currentFreeLocal += Type.getObjectType("java/lang/StringBuilder").getSize();

                        // Now, pop dynamic arguments from the stack and store them in their assigned local variables.
                        // We pop from the top of the stack (last dynamic argument) and store into its corresponding local variable.
                        for (int i = dynamicArgTypes.length - 1; i >= 0; i--) {
                            Type argType = dynamicArgTypes[i];
                            super.visitVarInsn(argType.getOpcode(Opcodes.ISTORE), dynamicArgLocalVarIndices[i]);
                        }

                        // Generate StringBuilder initialization code
                        super.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                        super.visitInsn(Opcodes.DUP);
                        super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                        super.visitVarInsn(Opcodes.ASTORE, sbLocalVarIndex); // Store StringBuilder in local variable

                        StringBuilder currentPart = new StringBuilder();

                        // Iterate through the recipe string, processing constant parts, dynamic arguments, and static arguments
                        for (char c : recipe.toCharArray()) {
                            if (c == '\u0001') { // Dynamic argument placeholder
                                // Append the currently accumulated constant string part
                                if (!currentPart.isEmpty()) {
                                    String constantString = currentPart.toString();
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder
                                    if (canEncrypted(constantString)) {
                                        encryptAndWrite(constantString, this);
                                    } else {
                                        super.visitLdcInsn(constantString);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                                    super.visitInsn(Opcodes.POP); // Pop the returned StringBuilder reference to maintain stack balance
                                    currentPart.setLength(0);
                                }

                                // Append dynamic argument (loaded from local variable)
                                if (dynamicArgCounter < dynamicArgTypes.length) {
                                    Type argType = dynamicArgTypes[dynamicArgCounter];
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder
                                    super.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), dynamicArgLocalVarIndices[dynamicArgCounter]); // Load dynamic argument
                                    dynamicArgCounter++;

                                    String appendDesc;
                                    // Choose the appropriate append method based on argument type
                                    if (argType.getSort() == Type.OBJECT || argType.getSort() == Type.ARRAY) {
                                        appendDesc = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else {
                                        appendDesc = "(" + argType.getDescriptor() + ")Ljava/lang/StringBuilder;";
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDesc, false);
                                    super.visitInsn(Opcodes.POP); // Pop the returned StringBuilder reference
                                } else {
                                    System.err.println("StringConcatFactory recipe: dynamic argument mismatch!");
                                }
                            } else if (c == '\u0002') { // Static argument placeholder
                                // Append the currently accumulated constant string part
                                if (!currentPart.isEmpty()) {
                                    String constantString = currentPart.toString();
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder
                                    if (canEncrypted(constantString)) {
                                        encryptAndWrite(constantString, this);
                                    } else {
                                        super.visitLdcInsn(constantString);
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                                    super.visitInsn(Opcodes.POP); // Pop the returned StringBuilder reference
                                    currentPart.setLength(0);
                                }

                                // Append static argument (fetch from bsmArgs and push onto the stack)
                                if (staticArgIndex < staticConcatArgs.length) {
                                    Object staticArg = staticConcatArgs[staticArgIndex++];
                                    super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder

                                    String appendDescForStatic;
                                    // Push static argument onto the stack and call the appropriate append method
                                    if (staticArg instanceof String) {
                                        String s = (String) staticArg;
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
                                    } else if (staticArg instanceof Type) { // Class constant
                                        Type type = (Type) staticArg;
                                        super.visitLdcInsn(type);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else if (staticArg instanceof Handle) { // MethodHandle constant
                                        Handle handle = (Handle) staticArg;
                                        super.visitLdcInsn(handle);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    } else {
                                        // Other unknown types, treat as Object
                                        super.visitLdcInsn(staticArg);
                                        appendDescForStatic = "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
                                    }
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", appendDescForStatic, false);
                                    super.visitInsn(Opcodes.POP); // Pop the returned StringBuilder reference
                                } else {
                                    System.err.println("StringConcatFactory recipe: static argument mismatch!");
                                }
                            } else {
                                currentPart.append(c);
                            }
                        }

                        // Append any remaining constant string part
                        if (!currentPart.isEmpty()) {
                            String constantString = currentPart.toString();
                            super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder
                            if (canEncrypted(constantString)) {
                                encryptAndWrite(constantString, this);
                            } else {
                                super.visitLdcInsn(constantString);
                            }
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                            super.visitInsn(Opcodes.POP); // Pop the returned StringBuilder reference
                        }

                        // Call toString() method to get the final string
                        super.visitVarInsn(Opcodes.ALOAD, sbLocalVarIndex); // Load StringBuilder
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);

                    } else {
                        // For other INVOKEDYNAMIC instructions, delegate directly to the super method
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

        return value.length() < (65536 >> 2) && myStrGuard.apply(value);
    }


    private boolean isSpecialString(String value) {
        // More comprehensively exclude compiler-generated special strings
        // Removed value.contains("$") as it was too broad for user-defined strings
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
