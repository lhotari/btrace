/*
 * Copyright (c) 2008, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.sun.btrace.runtime;

import com.sun.btrace.annotations.Sampled;
import com.sun.btrace.org.objectweb.asm.AnnotationVisitor;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import com.sun.btrace.org.objectweb.asm.Label;
import com.sun.btrace.org.objectweb.asm.Opcodes;
import com.sun.btrace.org.objectweb.asm.Type;
import static com.sun.btrace.org.objectweb.asm.Opcodes.*;
import static com.sun.btrace.runtime.Constants.*;
import static com.sun.btrace.runtime.Verifier.BTRACE_DURATION_DESC;
import static com.sun.btrace.runtime.Verifier.BTRACE_RETURN_DESC;
import static com.sun.btrace.runtime.Verifier.BTRACE_TARGETINSTANCE_DESC;
import static com.sun.btrace.runtime.Verifier.BTRACE_TARGETMETHOD_DESC;
import com.sun.btrace.services.api.Service;
import java.util.List;

/**
 * This class verifies that the BTrace "action" method is
 * safe - boundedness and read-only rules are checked
 * such as no backward jumps (loops), no throw/new/invoke etc.
 *
 * @author A. Sundararajan
 */
final public class MethodVerifier extends StackTrackingMethodVisitor {

    final private static Set<String> primitiveWrapperTypes;
    final private static Set<String> unboxMethods;

    static {
        primitiveWrapperTypes = new HashSet<String>();
        unboxMethods = new HashSet<String>();

        primitiveWrapperTypes.add("java/lang/Boolean");
        primitiveWrapperTypes.add("java/lang/Byte");
        primitiveWrapperTypes.add("java/lang/Character");
        primitiveWrapperTypes.add("java/lang/Short");
        primitiveWrapperTypes.add("java/lang/Integer");
        primitiveWrapperTypes.add("java/lang/Long");
        primitiveWrapperTypes.add("java/lang/Float");
        primitiveWrapperTypes.add("java/lang/Double");
        unboxMethods.add("booleanValue");
        unboxMethods.add("byteValue");
        unboxMethods.add("charValue");
        unboxMethods.add("shortValue");
        unboxMethods.add("intValue");
        unboxMethods.add("longValue");
        unboxMethods.add("floatValue");
        unboxMethods.add("doubleValue");
    }

    private final Verifier verifier;
    private final BTraceConfigurator cfg;

    protected boolean asBTrace = false;
    protected Location loc;

    private final String className;
    private final String methodName;
    private final String methodDesc;
    private final int access;
    private final Map<Label, Label> labels;

    private Object delayedClzLoad = null;

    public MethodVerifier(Verifier v, BTraceConfigurator cfg, int access, String className, String methodName, String desc) {
        super(cfg, className, desc, ((access & Opcodes.ACC_STATIC) == Opcodes.ACC_STATIC));
        this.verifier = v;
        this.cfg = cfg;
        this.className = className;
        this.methodName = methodName;
        this.methodDesc = desc;
        this.access = access;
        labels = new HashMap<Label, Label>();
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc,
                                  boolean visible) {
        AnnotationVisitor av = super.visitAnnotation(desc, visible);

        if (desc.startsWith("Lcom/sun/btrace/annotations/")) {
            asBTrace = true;
        }

        return av;
    }

    @Override
    public AnnotationVisitor visitParameterAnnotation(int parameter, final String desc, boolean visible) {
        AnnotationVisitor av = super.visitParameterAnnotation(parameter, desc, visible);

        if (cfg.getOnMethod() != null) {
            if (desc.equals(BTRACE_RETURN_DESC) && cfg.getOnMethod().getReturnParameter() == -1) {
                reportError("return.desc.invalid", methodName + methodDesc + "(" + parameter + ")");
            }
            if (desc.equals(BTRACE_TARGETMETHOD_DESC) && cfg.getOnMethod().getTargetMethodOrFieldParameter() == -1) {
                reportError("called-method.desc.invalid", methodName + methodDesc + "(" + parameter + ")");
            }
            if (desc.equals(BTRACE_TARGETINSTANCE_DESC) && cfg.getOnMethod().getTargetInstanceParameter() == -1) {
                reportError("called-instance.desc.invalid", methodName + methodDesc + "(" + parameter + ")");
            }
            if (desc.equals(BTRACE_DURATION_DESC) && cfg.getOnMethod().getDurationParameter() == -1) {
                reportError("duration.desc.invalid", methodName + methodDesc + "(" + parameter + ")");
            }
        }

        return av;
    }

    @Override
    public void visitEnd() {
        if (asBTrace) { // only btrace handlers are enforced to be public
            if ((access & ACC_PUBLIC) == 0 && !methodName.equals(CLASS_INITIALIZER)) {
                reportError("method.should.be.public", methodName + methodDesc);
            }
            if (Type.getReturnType(methodDesc) != Type.VOID_TYPE) {
                reportError("return.type.should.be.void", methodName + methodDesc);
            }

            if (cfg.getOnMethod() != null) {
                validateSamplerLocation();
            }
        }

        labels.clear();
        super.visitEnd();
    }

    @Override
    public void visitFieldInsn(int opcode, String owner,
            String name, String desc) {
        if (opcode == PUTFIELD) {
            reportError("no.assignment");
        }

        if (opcode == PUTSTATIC) {
            if (!owner.equals(className)) {
                reportError("no.assignment");
            }
        }
        super.visitFieldInsn(opcode, owner, name, desc);
    }

    @Override
    public void visitInsn(int opcode) {
        switch (opcode) {
            case IASTORE:
            case LASTORE:
            case FASTORE:
            case DASTORE:
            case AASTORE:
            case BASTORE:
            case CASTORE:
            case SASTORE:
                reportError("no.assignment");
                break;
            case ATHROW:
                reportError("no.throw");
                break;
            case MONITORENTER:
            case MONITOREXIT:
                reportError("no.synchronized.blocks");
                break;
        }
        super.visitInsn(opcode);
    }

    @Override
    public void visitIntInsn(int opcode, int operand) {
        if (opcode == NEWARRAY) {
            reportError("no.array.creation");
        }
        super.visitIntInsn(opcode, operand);
    }

    @Override
    public void visitJumpInsn(int opcode, Label label) {
        if (labels.get(label) != null) {
            reportError("no.loops");
        }
        super.visitJumpInsn(opcode, label);
    }

    @Override
    public void visitLabel(Label label) {
        labels.put(label, label);
        super.visitLabel(label);
    }

    @Override
    public void visitLdcInsn(Object cst) {
        if (cst instanceof Type) {
            delayedClzLoad = cst;
        }
        super.visitLdcInsn(cst);
    }

    @Override
    public void visitMethodInsn(int opcode, String owner,
            String name, String desc, boolean itf) {
        switch (opcode) {
            case INVOKEVIRTUAL:
                if (isPrimitiveWrapper(owner) && unboxMethods.contains(name)) {
                    // allow primitive type unbox methods.
                    // These calls are generated by javac for auto-unboxing
                    // and can't be caught by source AST analyzer as well.
                } else if (owner.equals(Type.getInternalName(StringBuilder.class))) {
                   // allow string concatenation via StringBuilder
                } else {
                    List<StackItem> args = getMethodParams(desc, false);
                    if (!isServiceTarget(args.get(0))) {
                        reportError("no.method.calls", owner + "." + name + desc);
                    }
                }
                break;
            case INVOKEINTERFACE:
                reportError("no.method.calls", owner + "." + name + desc);
                break;
            case INVOKESPECIAL:
                if (owner.equals(JAVA_LANG_OBJECT) && name.equals(CONSTRUCTOR)) {
                    // allow object initializer
                } else if (owner.equals(Type.getInternalName(StringBuilder.class))) {
                    // allow string concatenation via StringBuilder
                } else {
                    reportError("no.method.calls", owner + "." + name + desc);
                }
                break;
            case INVOKESTATIC:
                if (owner.equals(SERVICE)) {
                    delayedClzLoad = null;
                } else if (!owner.equals(BTRACE_UTILS) &&
                    !owner.startsWith(BTRACE_UTILS + "$") &&
                    !owner.equals(className)) {
                    if ("valueOf".equals(name) && isPrimitiveWrapper(owner)) {
                        // allow primitive wrapper boxing methods.
                        // These calls are generated by javac for autoboxing
                        // and can't be caught sourc AST analyzer as well.
                    } else {
                        reportError("no.method.calls", owner + "." + name + desc);
                    }
                }
                break;
        }
        if (delayedClzLoad != null) {
            reportError("no.class.literals", delayedClzLoad.toString());
        }
        super.visitMethodInsn(opcode, owner, name, desc, itf);
    }

    @Override
    public void visitMultiANewArrayInsn(String desc, int dims) {
        reportError("no.array.creation");
    }

    @Override
    public void visitTryCatchBlock(Label start, Label end,
            Label handler, String type) {
        reportError("no.catch");
    }

    @Override
    public void visitTypeInsn(int opcode, String desc) {
        if (opcode == ANEWARRAY) {
            reportError("no.array.creation", desc);
        }
        if (opcode == NEW) {
            // allow StringBuilder creation for string concatenation
            if (!desc.equals(Type.getInternalName(StringBuilder.class))) {
                reportError("no.new.object", desc);
            }
        }
        super.visitTypeInsn(opcode, desc);
    }

    @Override
    public void visitVarInsn(int opcode, int var) {
        if (opcode == RET) {
            reportError("no.try");
        }
        super.visitVarInsn(opcode, var);
    }

    private void reportError(String err) {
        reportError(err, null);
    }

    private void reportError(String err, String msg) {
        verifier.reportError(err, msg);
    }

    private static boolean isPrimitiveWrapper(String type) {
        return primitiveWrapperTypes.contains(type);
    }

    private boolean isServiceTarget(StackItem si) {
        if (si instanceof ResultItem) {
            ResultItem ri = (ResultItem)si;
            if (ri.getOwner().equals(Type.getInternalName(Service.class))) {
                return true;
            } else if (ri.getOwner().equals(className) &&
                verifier.injectedFields.contains(ri.getName())) {
                return true;
            }
        }
        for(StackItem p : si.getParents()) {
            if (isServiceTarget(p)) {
                return true;
            }
        }
        return false;
    }

    private void validateSamplerLocation() {
        if (cfg.getOnMethod() == null && cfg.isSampled()) {
            reportError("sampler.invalid.location", methodName + methodDesc);
            return;
        }
        if (cfg.getOnMethod().getSamplerKind() != Sampled.Sampler.None) {
            switch (cfg.getOnMethod().getLocation().getValue()) {
                case ENTRY:
                case RETURN:
                case ERROR:
                case CALL: {
                    // ok
                    break;
                }
                default: {
                    reportError("sampler.invalid.location", methodName + methodDesc);
                }
            }
        }
    }
}
