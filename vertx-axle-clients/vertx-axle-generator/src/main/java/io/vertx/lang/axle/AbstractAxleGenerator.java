package io.vertx.lang.axle;

import io.vertx.codegen.Helper;
import io.vertx.codegen.*;
import io.vertx.codegen.annotations.ModuleGen;
import io.vertx.codegen.annotations.VertxGen;
import io.vertx.codegen.doc.Doc;
import io.vertx.codegen.doc.Tag;
import io.vertx.codegen.doc.Token;
import io.vertx.codegen.type.*;

import javax.lang.model.element.Element;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.vertx.codegen.type.ClassKind.*;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

public abstract class AbstractAxleGenerator extends Generator<ClassModel> {
    private String id;

    public AbstractAxleGenerator(String id) {
        this.id = id;
        this.kinds = Collections.singleton("class");
    }

    public static MethodKind methodKind(MethodInfo methodInfo) {
        List<ParamInfo> params = methodInfo.getParams();
        int lastParamIndex = params.size() - 1;
        if (lastParamIndex >= 0) {
            TypeInfo lastParamType = params.get(lastParamIndex).getType();
            if (lastParamType.getKind() == ClassKind.HANDLER) {
                TypeInfo typeArg = ((ParameterizedTypeInfo) lastParamType).getArgs().get(0);
                if (typeArg.getKind() == ClassKind.ASYNC_RESULT) {
                    return MethodKind.FUTURE;
                } else {
                    return MethodKind.HANDLER;
                }
            }
        }
        return MethodKind.OTHER;
    }

    @Override
    public Collection<Class<? extends Annotation>> annotations() {
        return Arrays.asList(VertxGen.class, ModuleGen.class);
    }

    @Override
    public String filename(ClassModel model) {
        ModuleInfo module = model.getModule();
        return module.translateQualifiedName(model.getFqn(), id) + ".java";
    }

    @Override
    public String render(ClassModel model, int index, int size, Map<String, Object> session) {
        StringWriter sw = new StringWriter();
        PrintWriter writer = new PrintWriter(sw);
        generateClass(model, writer);
        return sw.toString();
    }

    private void generateClass(ClassModel model, PrintWriter writer) {
        ClassTypeInfo type = model.getType();

        generateLicense(writer);

        writer.print("package ");
        writer.print(type.translatePackageName(id));
        writer.println(";");
        writer.println();

        writer.println("import java.util.Map;");
        genRxImports(model, writer);

        writer.println();
        generateDoc(model, writer);
        writer.println();

        writer.print("@io.vertx.lang.axle.Gen(");
        writer.print(type.getName());
        writer.println(".class)");

        writer.print("public ");
        if (model.isConcrete()) {
            writer.print("class");
        } else {
            writer.print("interface");
        }
        writer.print(" ");
        writer.print(Helper.getSimpleName(model.getIfaceFQCN()));

        if (model.isConcrete() && model.getConcreteSuperType() != null) {
            writer.print(" extends ");
            writer.print(genTypeName(model.getConcreteSuperType()));
        }

        List<String> interfaces = new ArrayList<>();
        if ("io.vertx.core.buffer.Buffer".equals(type.getName())) {
            interfaces.add("io.vertx.core.shareddata.impl.ClusterSerializable");
        }
        interfaces.addAll(model.getAbstractSuperTypes().stream().map(this::genTypeName).collect(toList()));
        if (model.isHandler()) {
            interfaces.add("io.vertx.core.Handler<" + genTypeName(model.getHandlerArg()) + ">");
        }
        if (model.isIterable()) {
            interfaces.add("java.lang.Iterable<" + genTypeName(model.getIterableArg()) + ">");
        }
        if (model.isIterator()) {
            interfaces.add("java.util.Iterator<" + genTypeName(model.getIteratorArg()) + ">");
        }
        if (model.isFunction()) {
            TypeInfo[] functionArgs = model.getFunctionArgs();
            interfaces.add("java.util.function.Function<" + genTypeName(functionArgs[0]) + ", " + genTypeName(functionArgs[1]) + ">");
        }

        if (!interfaces.isEmpty()) {
            writer.print(interfaces.stream().collect(joining(", ", model.isConcrete() ? " implements " : " extends ", "")));
        }

        writer.println(" {");
        writer.println();

        if (model.isConcrete()) {
            if ("io.vertx.core.buffer.Buffer".equals(type.getName())) {
                writer.println("  @Override");
                writer.println("  public void writeToBuffer(io.vertx.core.buffer.Buffer buffer) {");
                writer.println("    delegate.writeToBuffer(buffer);");
                writer.println("  }");
                writer.println();
                writer.println("  @Override");
                writer.println("  public int readFromBuffer(int pos, io.vertx.core.buffer.Buffer buffer) {");
                writer.println("    return delegate.readFromBuffer(pos, buffer);");
                writer.println("  }");
                writer.println();
            }

            List<MethodInfo> methods = model.getMethods();
            if (methods.stream().noneMatch(it -> it.getParams().isEmpty() && "toString".equals(it.getName()))) {
                writer.println("  @Override");
                writer.println("  public String toString() {");
                writer.println("    return delegate.toString();");
                writer.println("  }");
                writer.println();
            }

            writer.println("  @Override");
            writer.println("  public boolean equals(Object o) {");
            writer.println("    if (this == o) return true;");
            writer.println("    if (o == null || getClass() != o.getClass()) return false;");
            writer.print("    ");
            writer.print(type.getSimpleName());
            writer.print(" that = (");
            writer.print(type.getSimpleName());
            writer.println(") o;");
            writer.println("    return delegate.equals(that.delegate);");
            writer.println("  }");
            writer.println("  ");

            writer.println("  @Override");
            writer.println("  public int hashCode() {");
            writer.println("    return delegate.hashCode();");
            writer.println("  }");
            writer.println();

            if (model.isIterable()) {
                generateIterableMethod(model, writer);
            }
            if (model.isIterator()) {
                generateIteratorMethods(model, writer);
            }
            if (model.isFunction()) {
                generateFunctionMethod(model, writer);
            }

            generateClassBody(model, model.getIfaceSimpleName(), writer);
        } else {
            writer.print("  ");
            writer.print(type.getName());
            writer.println(" getDelegate();");
            writer.println();

            getGenMethods(model).forEach(method -> {
                genMethodDecl(model, method, Collections.emptyList(), writer);
            });

            if (type.getRaw().getName().equals("io.vertx.core.streams.ReadStream")) {
                genReadStream(type.getParams(), writer);
            }
        }
        writer.println();
        writer.print("  public static ");
        writer.print(genOptTypeParamsDecl(type, " "));
        writer.print(type.getSimpleName());
        writer.print(genOptTypeParamsDecl(type, ""));
        writer.print(" newInstance(");
        writer.print(type.getName());
        writer.println(" arg) {");

        writer.print("    return arg != null ? new ");
        writer.print(type.getSimpleName());
        if (!model.isConcrete()) {
            writer.print("Impl");
        }
        writer.print(genOptTypeParamsDecl(type, ""));
        writer.println("(arg) : null;");
        writer.println("  }");

        if (type.getParams().size() > 0) {
            writer.println();
            writer.print("  public static ");
            writer.print(genOptTypeParamsDecl(type, " "));
            writer.print(type.getSimpleName());
            writer.print(genOptTypeParamsDecl(type, ""));
            writer.print(" newInstance(");
            writer.print(type.getName());
            writer.print(" arg");
            for (TypeParamInfo typeParam : type.getParams()) {
                writer.print(", io.vertx.lang.axle.TypeArg<");
                writer.print(typeParam.getName());
                writer.print("> __typeArg_");
                writer.print(typeParam.getName());
            }
            writer.println(") {");

            writer.print("    return arg != null ? new ");
            writer.print(type.getSimpleName());
            if (!model.isConcrete()) {
                writer.print("Impl");
            }
            writer.print(genOptTypeParamsDecl(type, ""));
            writer.print("(arg");
            for (TypeParamInfo typeParam : type.getParams()) {
                writer.print(", __typeArg_");
                writer.print(typeParam.getName());
            }
            writer.println(") : null;");
            writer.println("  }");
        }
        writer.println("}");

        if (!model.isConcrete()) {
            writer.println();
            writer.print("class ");
            writer.print(type.getSimpleName());
            writer.print("Impl");
            writer.print(genOptTypeParamsDecl(type, ""));
            writer.print(" implements ");
            writer.print(Helper.getSimpleName(model.getIfaceFQCN()));
            writer.println(" {");
            generateClassBody(model, type.getSimpleName() + "Impl", writer);
            writer.println("}");
        }
    }

    private void generateIterableMethod(ClassModel model, PrintWriter writer) {
        if (model.getMethods().stream().noneMatch(it -> it.getParams().isEmpty() && "iterator".equals(it.getName()))) {
            TypeInfo iterableArg = model.getIterableArg();

            writer.println("  @Override");
            writer.printf("  public java.util.Iterator<%s> iterator() {%n", genTypeName(iterableArg));

            if (iterableArg.getKind() == ClassKind.API) {
                writer.format("    java.util.function.Function<%s, %s> conv = %s::newInstance;%n", iterableArg.getName(), genTypeName(iterableArg.getRaw()), genTypeName(iterableArg));
                writer.println("    return new io.vertx.lang.axle.MappingIterator<>(delegate.iterator(), conv);");
            } else if (iterableArg.isVariable()) {
                String typeVar = iterableArg.getSimpleName();
                writer.format("    java.util.function.Function<%s, %s> conv = (java.util.function.Function<%s, %s>) __typeArg_0.wrap;%n", typeVar, typeVar, typeVar, typeVar);
                writer.println("    return new io.vertx.lang.axle.MappingIterator<>(delegate.iterator(), conv);");
            } else {
                writer.println("    return delegate.iterator();");
            }

            writer.println("  }");
            writer.println();
        }
    }

    private void generateIteratorMethods(ClassModel model, PrintWriter writer) {
        if (model.getMethods().stream().noneMatch(it -> it.getParams().isEmpty() && "hasNext".equals(it.getName()))) {
            writer.println("  @Override");
            writer.println("  public boolean hasNext() {");
            writer.println("    return delegate.hasNext();");
            writer.println("  }");
            writer.println();
        }
        if (model.getMethods().stream().noneMatch(it -> it.getParams().isEmpty() && "next".equals(it.getName()))) {
            TypeInfo iteratorArg = model.getIteratorArg();

            writer.println("  @Override");
            writer.printf("  public %s next() {%n", genTypeName(iteratorArg));

            if (iteratorArg.getKind() == ClassKind.API) {
                writer.format("    return %s.newInstance(delegate.next());%n", genTypeName(iteratorArg));
            } else if (iteratorArg.isVariable()) {
                writer.println("    return __typeArg_0.wrap(delegate.next());");
            } else {
                writer.println("    return delegate.next();");
            }

            writer.println("  }");
            writer.println();
        }
    }

    private void generateFunctionMethod(ClassModel model, PrintWriter writer) {
        if (model.getMethods().stream().noneMatch(it -> it.getParams().size() == 1 && "apply".equals(it.getName()))) {
            TypeInfo[] functionArgs = model.getFunctionArgs();
            TypeInfo inArg = functionArgs[0];
            TypeInfo outArg = functionArgs[1];

            writer.println("  @Override");
            writer.printf("  public %s apply(%s in) {%n", genTypeName(outArg), genTypeName(inArg));
            writer.printf("    %s ret;%n", outArg.getName());

            if (inArg.getKind() == ClassKind.API) {
                writer.println("    ret = getDelegate().apply(in.getDelegate());");
            } else if (inArg.isVariable()) {
                String typeVar = inArg.getSimpleName();
                writer.format("    java.util.function.Function<%s, %s> inConv = (java.util.function.Function<%s, %s>) __typeArg_0.unwrap;%n", typeVar, typeVar, typeVar, typeVar);
                writer.println("    ret = getDelegate().apply(inConv.apply);");
            } else {
                writer.println("    ret = getDelegate().apply(in);");
            }

            if (outArg.getKind() == ClassKind.API) {
                writer.format("    java.util.function.Function<%s, %s> outConv = %s::newInstance;%n", outArg.getName(), genTypeName(outArg.getRaw()), genTypeName(outArg));
                writer.println("    return outConv.apply(ret);");
            } else if (outArg.isVariable()) {
                String typeVar = outArg.getSimpleName();
                writer.format("    java.util.function.Function<%s, %s> outConv = (java.util.function.Function<%s, %s>) __typeArg_1.wrap;%n", typeVar, typeVar, typeVar, typeVar);
                writer.println("    return outConv.apply(ret);");
            } else {
                writer.println("    return delegate.iterator();");
            }

            writer.println("  }");
            writer.println();
        }
    }

    protected abstract void genReadStream(List<? extends TypeParamInfo> typeParams, PrintWriter writer);

    private void generateClassBody(ClassModel model, String constructor, PrintWriter writer) {
        ClassTypeInfo type = model.getType();
        String simpleName = type.getSimpleName();
        if (model.isConcrete()) {
            writer.print("  public static final io.vertx.lang.axle.TypeArg<");
            writer.print(simpleName);
            writer.print("> __TYPE_ARG = new io.vertx.lang.axle.TypeArg<>(");
            writer.print("    obj -> new ");
            writer.print(simpleName);
            writer.print("((");
            writer.print(type.getName());
            writer.println(") obj),");
            writer.print("    ");
            writer.print(simpleName);
            writer.println("::getDelegate");
            writer.println("  );");
            writer.println();
        }
        writer.print("  private final ");
        writer.print(Helper.getNonGenericType(model.getIfaceFQCN()));
        List<TypeParamInfo.Class> typeParams = model.getTypeParams();
        if (typeParams.size() > 0) {
            writer.print(typeParams.stream().map(TypeParamInfo.Class::getName).collect(joining(",", "<", ">")));
        }
        writer.println(" delegate;");

        for (TypeParamInfo.Class typeParam : typeParams) {
            writer.print("  public final io.vertx.lang.axle.TypeArg<");
            writer.print(typeParam.getName());
            writer.print("> __typeArg_");
            writer.print(typeParam.getIndex());
            writer.println(";");
        }
        writer.println("  ");
        generateConstructorWithDelegateParameter(model, constructor, writer, typeParams);
        generateConstructorWithGenericType(model, constructor, writer, typeParams);
        generateEmptyConstructor(model, constructor, writer, type, typeParams);

        if (model.isReadStream()) {
            genToObservable(model.getReadStreamArg(), writer);
        }
        List<String> cacheDecls = new ArrayList<>();

        //
        Stream<MethodInfo> list = getGenMethods(model);
        list.forEach(method -> genMethods(model, method, cacheDecls, writer));

        for (ConstantInfo constant : model.getConstants()) {
            genConstant(model, constant, writer);
        }

        for (String cacheDecl : cacheDecls) {
            writer.print("  ");
            writer.print(cacheDecl);
            writer.println(";");
        }
    }

    private void generateEmptyConstructor(ClassModel model, String constructor, PrintWriter writer, ClassTypeInfo type, List<TypeParamInfo.Class> typeParams) {
        // Constructor without parameter, used by CDI
        writer.println("  /**");
        writer.println("  * Empty constructor used by CDI, do not use this constructor directly.");
        writer.println("  **/");
        writer.print("  ");
        writer.print(constructor);
        writer.print("() {");
        if (model.isConcrete() && model.getConcreteSuperType() != null) {
            writer.println("    super(null);");
        }
        writer.println("    this.delegate = null;");
        for (TypeParamInfo.Class typeParam : typeParams) {
            writer.print("    this.__typeArg_");
            writer.print(typeParam.getIndex());
            writer.print(" = io.vertx.lang.axle.TypeArg.unknown();");
        }
        writer.println("  }");
        writer.println();

        writer.print("  public ");
        writer.print(type.getName());
        writer.println(" getDelegate() {");
        writer.println("    return delegate;");
        writer.println("  }");
        writer.println();
    }

    private void generateConstructorWithGenericType(ClassModel model, String constructor, PrintWriter writer, List<TypeParamInfo.Class> typeParams) {
        // Constructor with generic type
        if (typeParams.size() > 0) {
            writer.print("  public ");
            writer.print(constructor);
            writer.print("(");
            writer.print(Helper.getNonGenericType(model.getIfaceFQCN()));
            writer.print(" delegate");
            for (TypeParamInfo.Class typeParam : typeParams) {
                writer.print(", io.vertx.lang.axle.TypeArg<");
                writer.print(typeParam.getName());
                writer.print("> typeArg_");
                writer.print(typeParam.getIndex());
            }
            writer.println(") {");
            if (model.isConcrete() && model.getConcreteSuperType() != null) {
                writer.println("    super(delegate);");
            }
            writer.println("    this.delegate = delegate;");
            for (TypeParamInfo.Class typeParam : typeParams) {
                writer.print("    this.__typeArg_");
                writer.print(typeParam.getIndex());
                writer.print(" = typeArg_");
                writer.print(typeParam.getIndex());
                writer.println(";");
            }
            writer.println("  }");
            writer.println();
        }
    }

    private void generateConstructorWithDelegateParameter(ClassModel model, String constructor, PrintWriter writer, List<TypeParamInfo.Class> typeParams) {
        // Constructor taking delegate as parameter.
        writer.print("  public ");
        writer.print(constructor);
        writer.print("(");
        writer.print(Helper.getNonGenericType(model.getIfaceFQCN()));
        writer.println(" delegate) {");

        if (model.isConcrete() && model.getConcreteSuperType() != null) {
            writer.println("    super(delegate);");
        }
        writer.println("    this.delegate = delegate;");
        for (TypeParamInfo.Class typeParam : typeParams) {
            writer.print("    this.__typeArg_");
            writer.print(typeParam.getIndex());
            writer.print(" = io.vertx.lang.axle.TypeArg.unknown();");
        }
        writer.println("  }");
        writer.println();
    }

    /**
     * Build the list of method to generate.
     *
     * @param model the class model
     * @return the list of methods as a stream
     */
    private Stream<MethodInfo> getGenMethods(ClassModel model) {
        List<List<MethodInfo>> list = new ArrayList<>();
        list.add(model.getMethods());
        list.add(model.getAnyJavaTypeMethods());
        list.forEach(methods -> {

            // First pass: filter conflicting overrides, that will partly filter it
            ListIterator<MethodInfo> it = methods.listIterator();
            while (it.hasNext()) {
                MethodInfo method = it.next();
                if (methodKind(method) != MethodKind.FUTURE) {
                    // Has it been removed above ?
                    Predicate<MethodInfo> pred;
                    if (method.isOwnedBy(model.getType())) {
                        pred = other -> isOverride(method, other);
                    } else {
                        pred = other -> {
/*
                            if (isOverride(method, other)) {
                                Set<ClassTypeInfo> tmp = new HashSet<>(method.getOwnerTypes());
                                tmp.removeAll(other.getOwnerTypes());
                                return tmp.isEmpty();
                            }
*/
                            return isOverride(method, other);
                        };
                    }
                    if (methods.stream()
                        .filter(m -> methodKind(m) == MethodKind.FUTURE)
                        .anyMatch(pred)) {
                        it.remove();;
                    }
                }
            }

            // Second pass: filter future methods that might be still conflict
            it = methods.listIterator();
            while (it.hasNext()) {
                MethodInfo meth = it.next();
                if (methodKind(meth) == MethodKind.FUTURE) {
                    boolean remove;
                    List<MethodInfo> abc = model.getMethodMap().getOrDefault(meth.getName(), Collections.emptyList());
                    if (meth.isOwnedBy(model.getType())) {
                        remove = abc.stream()
                            .filter(m -> methodKind(m) != MethodKind.FUTURE && isOverride(m, meth))
                            .anyMatch(m -> !m.isOwnedBy(model.getType()) || methods.contains(m));
                    } else {
                        remove = abc.stream()
                            .filter(other -> methodKind(other) != MethodKind.FUTURE)
                            .anyMatch(other -> {
                                if (methodKind(other) != MethodKind.FUTURE) {
                                    Set<ClassTypeInfo> tmp = new HashSet<>(other.getOwnerTypes());
                                    tmp.retainAll(meth.getOwnerTypes());
                                    return isOverride(meth, other) & !tmp.isEmpty();
                                }
                                return false;
                            });
                    }
                    if (remove) {
                        it.remove();
                    }
                }
            }
        });
        return list.stream().flatMap(Collection::stream);
    }

    protected abstract void genToObservable(TypeInfo streamType, PrintWriter writer);

    protected abstract void genMethods(ClassModel model, MethodInfo method, List<String> cacheDecls, PrintWriter writer);

    protected abstract void genCSMethod(boolean decl, ClassModel model, MethodInfo method, PrintWriter writer);

    protected abstract MethodInfo genConsumerMethod(MethodInfo method);

    protected abstract void genConsumerMethod(boolean decl, ClassModel model, MethodInfo method, PrintWriter writer);

    private boolean isOverride(MethodInfo s1, MethodInfo s2) {
        if (s1.getName().equals(s2.getName()) && s1.getParams().size() == s2.getParams().size() - 1) {
            for (int i = 0; i < s1.getParams().size(); i++) {
                if (!s1.getParams().get(i).getType().equals(s2.getParams().get(i).getType())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    protected final void genMethod(ClassModel model, MethodInfo method, List<String> cacheDecls, PrintWriter writer) {
        if (methodKind(method) == MethodKind.FUTURE) {
            genSimpleMethod(false, model, true, "__" + method.getName(), method, cacheDecls, writer);
            genCSMethod(false, model, method, writer);
        } else if (methodKind(method) == MethodKind.HANDLER) {
            genSimpleMethod(false, model, true, "__" + method.getName(), method, cacheDecls, writer);
            genConsumerMethod(false, model, method, writer);
        } else {
            genSimpleMethod(false, model, false, method.getName(), method, cacheDecls, writer);
        }
    }

    protected final void genMethodDecl(ClassModel model, MethodInfo method, List<String> cacheDecls, PrintWriter writer) {
        if (methodKind(method) == MethodKind.FUTURE) {
            genCSMethod(true, model, method, writer);
        } else if (methodKind(method) == MethodKind.HANDLER) {
            genConsumerMethod(true, model, method, writer);
        } else {
            genSimpleMethod(true, model, false, method.getName(), method, cacheDecls, writer);
        }
    }

    private void genConstant(ClassModel model, ConstantInfo constant, PrintWriter writer) {
        Doc doc = constant.getDoc();
        if (doc != null) {
            writer.println("  /**");
            Token.toHtml(doc.getTokens(), "   *", this::renderLinkToHtml, "\n", writer);
            writer.println("   */");
        }
        writer.print(model.isConcrete() ? "  public static final" : "");
        writer.format(" %s %s = %s;%n",
                genTypeName(constant.getType()),
                constant.getName(),
                genConvReturn(constant.getType(), null, model.getType().getName() + "." + constant.getName()));
    }

    protected void startMethodTemplate(boolean isPrivate, String methodName, MethodInfo method, String deprecated,
            PrintWriter writer) {
        Doc doc = method.getDoc();
        if (doc != null) {
            writer.println("  /**");
            Token.toHtml(doc.getTokens(), "   *", this::renderLinkToHtml, "\n", writer);
            for (ParamInfo param : method.getParams()) {
                writer.print("   * @param ");
                writer.print(param.getName());
                writer.print(" ");
                if (param.getDescription() != null) {
                    Token.toHtml(param.getDescription().getTokens(), "", this::renderLinkToHtml, "", writer);
                }
                writer.println();
            }
            if (!method.getReturnType().getName().equals("void")) {
                writer.print("   * @return ");
                if (method.getReturnDescription() != null) {
                    Token.toHtml(method.getReturnDescription().getTokens(), "", this::renderLinkToHtml, "", writer);
                }
                writer.println();
            }
            if (deprecated != null && deprecated.length() > 0) {
                writer.print("   * @deprecated ");
                writer.println(deprecated);
            }
            writer.println("   */");
        }
        if (method.isDeprecated() || deprecated != null && deprecated.length() > 0) {
            writer.println("  @Deprecated()");
        }
        writer.print("  " + (isPrivate ? "private" : "public") + " ");
        if (method.isStaticMethod()) {
            writer.print("static ");
        }
        if (method.getTypeParams().size() > 0) {
            writer.print(
              method.getTypeParams().stream().map(TypeParamInfo::getName).collect(joining(", ", "<", ">")));
            writer.print(" ");
        }
        writer.print(genTypeName(method.getReturnType()));
        writer.print(" ");
        writer.print(methodName);
        writer.print("(");
        writer.print(method.getParams().stream().map(it -> genTypeName(it.getType()) + " " + it.getName())
          .collect(joining(", ")));
        writer.print(")");

    }

    protected final String genTypeName(TypeInfo type) {
        if (type.isParameterized()) {
            ParameterizedTypeInfo pt = (ParameterizedTypeInfo) type;
            return genTypeName(pt.getRaw())
              + pt.getArgs().stream().map(this::genTypeName).collect(joining(", ", "<", ">"));
        } else if (type.getKind() == ClassKind.API) {
            return type.translateName(id);
        } else {
            return type.getSimpleName();
        }
    }

    protected String genCSMethodName(MethodInfo method) {
        return "_" + Character.toUpperCase(method.getName().charAt(0)) + method.getName().substring(1);
    }

    //  private void genInvokeDelegate(MethodInfo method, PrintWriter writer) {
    //
    //  }

    private void genSimpleMethod(boolean decl,
                                 ClassModel model,
                                 boolean isPrivate,
                                 String methodName,
                                 MethodInfo method,
                                 List<String> cacheDecls,
                                 PrintWriter writer) {
        startMethodTemplate(isPrivate, methodName, method, "", writer);
        if (decl) {
            writer.println(";");
            return;
        }
        writer.println(" { ");
        if (method.isFluent()) {
            writer.print("    ");
            writer.print(genInvokeDelegate(model, method));
            writer.println(";");
            if (method.getReturnType().isVariable()) {
                writer.print("    return (");
                writer.print(method.getReturnType().getName());
                writer.println(") this;");
            } else {
                writer.println("    return this;");
            }
        } else if (method.getReturnType().getName().equals("void")) {
            writer.print("    ");
            writer.print(genInvokeDelegate(model, method));
            writer.println(";");
        } else {
            if (method.isCacheReturn()) {
                writer.print("    if (cached_");
                writer.print(cacheDecls.size());
                writer.println(" != null) {");

                writer.print("      return cached_");
                writer.print(cacheDecls.size());
                writer.println(";");
                writer.println("    }");
            }
            String cachedType;
            TypeInfo returnType = method.getReturnType();
            if (method.getReturnType().getKind() == PRIMITIVE) {
                cachedType = ((PrimitiveTypeInfo) returnType).getBoxed().getName();
            } else {
                cachedType = genTypeName(returnType);
            }
            writer.print("    ");
            writer.print(genTypeName(returnType));
            writer.print(" ret = ");
            writer.print(genConvReturn(returnType, method, genInvokeDelegate(model, method)));
            writer.println(";");
            if (method.isCacheReturn()) {
                writer.print("    cached_");
                writer.print(cacheDecls.size());
                writer.println(" = ret;");
                cacheDecls.add("private" + (method.isStaticMethod() ? " static" : "") + " " + cachedType + " cached_"
                        + cacheDecls.size());
            }
            writer.println("    return ret;");
        }
        writer.println("  }");
        writer.println();
    }

    private void generateDoc(ClassModel model, PrintWriter writer) {
        ClassTypeInfo type = model.getType();
        Doc doc = model.getDoc();
        if (doc != null) {
            writer.println("/**");
            Token.toHtml(doc.getTokens(), " *", this::renderLinkToHtml, "\n", writer);
            writer.println(" *");
            writer.println(" * <p/>");
            writer.print(" * NOTE: This class has been automatically generated from the {@link ");
            writer.print(type.getName());
            writer.println(" original} non RX-ified interface using Vert.x codegen.");
            writer.println(" */");
        }

    }

    protected void genRxImports(ClassModel model, PrintWriter writer) {
        for (ClassTypeInfo importedType : model.getImportedTypes()) {
            if (importedType.getKind() == ClassKind.API) {
            } else {
                if (!importedType.getPackageName().equals("java.lang")) {
                    addImport(importedType, false, writer);
                }
            }
        }
    }

    private void addImport(ClassTypeInfo type, boolean translate, PrintWriter writer) {
        writer.print("import ");
        if (translate) {
            writer.print(type.translateName(id));
        } else {
            writer.print(type.toString());
        }
        writer.println(";");
    }

    private String genInvokeDelegate(ClassModel model, MethodInfo method) {
        StringBuilder ret;
        if (method.isStaticMethod()) {
            ret = new StringBuilder(Helper.getNonGenericType(model.getIfaceFQCN()));
        } else {
            ret = new StringBuilder("delegate");
        }
        ret.append(".").append(method.getName()).append("(");
        int index = 0;
        for (ParamInfo param : method.getParams()) {
            if (index > 0) {
                ret.append(", ");
            }
            TypeInfo type = param.getType();
            if (type.isParameterized() && type.getRaw().getName().equals("rx.Observable")) {
                String adapterFunction;
                ParameterizedTypeInfo parameterizedType = (ParameterizedTypeInfo) type;

                if (parameterizedType.getArg(0).isVariable()) {
                    adapterFunction = "java.util.function.Function.identity()";
                } else {
                    adapterFunction = "obj -> (" + parameterizedType.getArg(0).getRaw().getName() + ")obj.getDelegate()";
                }
                ret.append("io.vertx.axle.java.ReadStreamSubscriber.asReadStream(").append(param.getName()).append(",")
                        .append(adapterFunction).append(").resume()");
            } else if (type.isParameterized() && (type.getRaw().getName().equals("org.reactivestreams.Publisher"))) {
                String adapterFunction;
                ParameterizedTypeInfo parameterizedType = (ParameterizedTypeInfo) type;
                if (parameterizedType.getArg(0).isVariable()) {
                    adapterFunction = "java.util.function.Function.identity()";
                } else {
                    adapterFunction = "obj -> (" + parameterizedType.getArg(0).getRaw().getName() + ")obj.getDelegate()";
                }
                ret.append("io.vertx.axle.ReadStreamSubscriber.asReadStream(").append(param.getName()).append(",")
                        .append(adapterFunction).append(").resume()");
            } else {
                ret.append(genConvParam(type, method, param.getName()));
            }
            index = index + 1;
        }
        ret.append(")");
        return ret.toString();
    }

    private boolean isSameType(TypeInfo type, MethodInfo method) {
        ClassKind kind = type.getKind();
        if (kind.basic || kind.json || kind == DATA_OBJECT || kind == ENUM || kind == OTHER || kind == THROWABLE
                || kind == VOID) {
            return true;
        } else if (kind == OBJECT) {
            if (type.isVariable()) {
                return !isReified((TypeVariableInfo) type, method);
            } else {
                return true;
            }
        } else if (type.isParameterized()) {
            ParameterizedTypeInfo parameterizedTypeInfo = (ParameterizedTypeInfo) type;
            if (kind == LIST || kind == SET || kind == ASYNC_RESULT) {
                return isSameType(parameterizedTypeInfo.getArg(0), method);
            } else if (kind == MAP) {
                return isSameType(parameterizedTypeInfo.getArg(1), method);
            } else if (kind == HANDLER) {
                return isSameType(parameterizedTypeInfo.getArg(0), method);
            } else if (kind == FUNCTION) {
                return isSameType(parameterizedTypeInfo.getArg(0), method)
                        && isSameType(parameterizedTypeInfo.getArg(1), method);
            }
        }
        return false;
    }

    private String genConvParam(TypeInfo type, MethodInfo method, String expr) {
        ClassKind kind = type.getKind();
        if (isSameType(type, method)) {
            return expr;
        } else if (kind == OBJECT) {
            if (type.isVariable()) {
                String typeArg = genTypeArg((TypeVariableInfo) type, method);
                if (typeArg != null) {
                    return typeArg + ".<" + type.getName() + ">unwrap(" + expr + ")";
                }
            }
            return expr;
        } else if (kind == API) {
            return expr + ".getDelegate()";
        } else if (kind == CLASS_TYPE) {
            return "io.vertx.lang.axle.Helper.unwrap(" + expr + ")";
        } else if (type.isParameterized()) {
            ParameterizedTypeInfo parameterizedTypeInfo = (ParameterizedTypeInfo) type;
            if (kind == HANDLER) {
                TypeInfo eventType = parameterizedTypeInfo.getArg(0);
                ClassKind eventKind = eventType.getKind();
                if (eventKind == ASYNC_RESULT) {
                    TypeInfo resultType = ((ParameterizedTypeInfo) eventType).getArg(0);
                    return "new Handler<AsyncResult<" + resultType.getName() + ">>() {\n" +
                            "      public void handle(AsyncResult<" + resultType.getName() + "> ar) {\n" +
                            "        if (ar.succeeded()) {\n" +
                            "          " + expr + ".handle(io.vertx.core.Future.succeededFuture("
                            + genConvReturn(resultType, method, "ar.result()") + "));\n" +
                            "        } else {\n" +
                            "          " + expr + ".handle(io.vertx.core.Future.failedFuture(ar.cause()));\n" +
                            "        }\n" +
                            "      }\n" +
                            "    }";
                } else {
                    return "new Handler<" + eventType.getName() + ">() {\n" +
                            "      public void handle(" + eventType.getName() + " event) {\n" +
                            "        " + expr + ".handle(" + genConvReturn(eventType, method, "event") + ");\n" +
                            "      }\n" +
                            "    }";
                }
            } else if (kind == FUNCTION) {
                TypeInfo argType = parameterizedTypeInfo.getArg(0);
                TypeInfo retType = parameterizedTypeInfo.getArg(1);
                return "new java.util.function.Function<" + argType.getName() + "," + retType.getName() + ">() {\n" +
                        "      public " + retType.getName() + " apply(" + argType.getName() + " arg) {\n" +
                        "        " + genTypeName(retType) + " ret = " + expr + ".apply(" + genConvReturn(argType, method, "arg")
                        + ");\n" +
                        "        return " + genConvParam(retType, method, "ret") + ";\n" +
                        "      }\n" +
                        "    }";
            } else if (kind == LIST || kind == SET) {
                return expr + ".stream().map(elt -> " + genConvParam(parameterizedTypeInfo.getArg(0), method, "elt")
                        + ").collect(java.util.stream.Collectors.to" + type.getRaw().getSimpleName() + "())";
            } else if (kind == MAP) {
                return expr + ".entrySet().stream().collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> "
                        + genConvParam(parameterizedTypeInfo.getArg(1), method, "e.getValue()") + "))";
            }
        }
        return expr;
    }

    private boolean isReified(TypeVariableInfo typeVar, MethodInfo method) {
        if (typeVar.isClassParam()) {
            return true;
        } else {
            TypeArgExpression typeArg = method.resolveTypeArg(typeVar);
            return typeArg != null && typeArg.isClassType();
        }
    }

    private String genTypeArg(TypeVariableInfo typeVar, MethodInfo method) {
        if (typeVar.isClassParam()) {
            return "__typeArg_" + typeVar.getParam().getIndex();
        } else {
            TypeArgExpression typeArg = method.resolveTypeArg(typeVar);
            if (typeArg != null) {
                if (typeArg.isClassType()) {
                    return "io.vertx.lang.axle.TypeArg.of(" + typeArg.getParam().getName() + ")";
                } else {
                    return typeArg.getParam().getName() + ".__typeArg_" + typeArg.getIndex();
                }
            }
        }
        return null;
    }

    //  private boolean hasReadStream(MethodInfo method) {
    //    for (ParamInfo param : method.getParams()) {
    //      if (param.getType().isParameterized() && param.getType().getRaw().getName().equals("io.vertx.core.streams.ReadStream")) {
    //        return true;
    //      }
    //    }
    //    return false;
    //  }

    private String genConvReturn(TypeInfo type, MethodInfo method, String expr) {
        ClassKind kind = type.getKind();
        if (kind == OBJECT) {
            if (type.isVariable()) {
                String typeArg = genTypeArg((TypeVariableInfo) type, method);
                if (typeArg != null) {
                    return "(" + type.getName() + ")" + typeArg + ".wrap(" + expr + ")";
                }
            }
            return "(" + type.getSimpleName() + ") " + expr;
        } else if (isSameType(type, method)) {
            return expr;
        } else if (kind == API) {
            StringBuilder tmp = new StringBuilder(type.getRaw().translateName(id));
            tmp.append(".newInstance(");
            tmp.append(expr);
            if (type.isParameterized()) {
                ParameterizedTypeInfo parameterizedTypeInfo = (ParameterizedTypeInfo) type;
                for (TypeInfo arg : parameterizedTypeInfo.getArgs()) {
                    tmp.append(", ");
                    ClassKind argKind = arg.getKind();
                    if (argKind == API) {
                        tmp.append("(io.vertx.lang.axle.TypeArg)").append(arg.getRaw().translateName(id)).append(".__TYPE_ARG");
                    } else {
                        String typeArg = "io.vertx.lang.axle.TypeArg.unknown()";
                        if (argKind == OBJECT && arg.isVariable()) {
                            String resolved = genTypeArg((TypeVariableInfo) arg, method);
                            if (resolved != null) {
                                typeArg = resolved;
                            }
                        }
                        tmp.append(typeArg);
                    }
                }
            }
            tmp.append(")");
            return tmp.toString();
        } else if (type.isParameterized()) {
            ParameterizedTypeInfo parameterizedTypeInfo = (ParameterizedTypeInfo) type;
            if (kind == HANDLER) {
                TypeInfo abc = parameterizedTypeInfo.getArg(0);
                if (abc.getKind() == ASYNC_RESULT) {
                    TypeInfo tutu = ((ParameterizedTypeInfo) abc).getArg(0);
                    return "new Handler<AsyncResult<" + genTypeName(tutu) + ">>() {\n" +
                            "      public void handle(AsyncResult<" + genTypeName(tutu) + "> ar) {\n" +
                            "        if (ar.succeeded()) {\n" +
                            "          " + expr + ".handle(io.vertx.core.Future.succeededFuture("
                            + genConvParam(tutu, method, "ar.result()") + "));\n" +
                            "        } else {\n" +
                            "          " + expr + ".handle(io.vertx.core.Future.failedFuture(ar.cause()));\n" +
                            "        }\n" +
                            "      }\n" +
                            "    }";
                } else {
                    return "new Handler<" + genTypeName(abc) + ">() {\n" +
                            "      public void handle(" + genTypeName(abc) + " event) {\n" +
                            "          " + expr + ".handle(" + genConvParam(abc, method, "event") + ");\n" +
                            "      }\n" +
                            "    }";
                }
            } else if (kind == LIST || kind == SET) {
                return expr + ".stream().map(elt -> " + genConvReturn(parameterizedTypeInfo.getArg(0), method, "elt")
                        + ").collect(java.util.stream.Collectors.to" + type.getRaw().getSimpleName() + "())";
            }
        }
        return expr;
    }

    private String genOptTypeParamsDecl(ClassTypeInfo type, String deflt) {
        if (type.getParams().size() > 0) {
            return type.getParams().stream().map(TypeParamInfo::getName).collect(joining(",", "<", ">"));
        } else {
            return deflt;
        }
    }

    private void generateLicense(PrintWriter writer) {
        writer.println("/*");
        writer.println(" * Copyright 2014 Red Hat, Inc.");
        writer.println(" *");
        writer.println(" * Red Hat licenses this file to you under the Apache License, version 2.0");
        writer.println(" * (the \"License\"); you may not use this file except in compliance with the");
        writer.println(" * License.  You may obtain a copy of the License at:");
        writer.println(" *");
        writer.println(" * http://www.apache.org/licenses/LICENSE-2.0");
        writer.println(" *");
        writer.println(" * Unless required by applicable law or agreed to in writing, software");
        writer.println(" * distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT");
        writer.println(" * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the");
        writer.println(" * License for the specific language governing permissions and limitations");
        writer.println(" * under the License.");
        writer.println(" */");
        writer.println();
    }

    private String renderLinkToHtml(Tag.Link link) {
        ClassTypeInfo rawType = link.getTargetType().getRaw();
        if (rawType.getModule() != null) {
            String label = link.getLabel().trim();
            if (rawType.getKind() == DATA_OBJECT) {
                return "{@link " + rawType.getName() + "}";
            } else {
                if (rawType.getKind() == ClassKind.API) {
                    Element elt = link.getTargetElement();
                    String eltKind = elt.getKind().name();
                    String ret = "{@link " + rawType.translateName(id);
                    if ("METHOD".equals(eltKind)) {
                        /* todo find a way for translating the complete signature */
                        ret += "#" + elt.getSimpleName().toString();
                    }
                    if (label.length() > 0) {
                        ret += " " + label;
                    }
                    ret += "}";
                    return ret;
                }
            }
        }
        return "{@link " + rawType.getName() + "}";
    }
}
