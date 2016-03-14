/*     / \____  _    _  ____   ______  / \ ____  __    _______
 *    /  /    \/ \  / \/    \ /  /\__\/  //    \/  \  //  /\__\   JΛVΛSLΛNG
 *  _/  /  /\  \  \/  /  /\  \\__\\  \  //  /\  \ /\\/ \ /__\ \   Copyright 2014-2016 Javaslang, http://javaslang.io
 * /___/\_/  \_/\____/\_/  \_/\__\/__/\__\_/  \_//  \__/\_____/   Licensed under the Apache License, Version 2.0
 */
package javaslang.match;

import javaslang.match.annotation.Patterns;
import javaslang.match.annotation.Unapply;
import javaslang.match.generator.Generator;
import javaslang.match.model.ClassModel;
import javaslang.match.model.MethodModel;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * A code generator for Javaslang <em>structural pattern matching</em> patterns.
 * <p>
 * <strong>Note:</strong>
 * <p>
 * If javac complains {@code [WARNING] No processor claimed any of these annotations: ...}
 * we need to provide the compiler arg {@code -Xlint:-processing}.
 * <p>
 * See <a href="https://bugs.openjdk.java.net/browse/JDK-6999068">JDK-6999068 bug</a>.
 *
 * @author Daniel Dietrich
 * @since 2.0.0
 */
// See Difference between Element, Type and Mirror: http://stackoverflow.com/a/2127320/1110815
public class PatternsProcessor extends AbstractProcessor {

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        // we do not use @SupportedAnnotationTypes in order to be type-safe
        return Collections.singleton(Patterns.class.getName());
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        // intended to be used with Java 8+
        return SourceVersion.latestSupported();
    }

    /**
     * Gathers annotated elements, transforms elements to a generator model and generates the model to code.
     *
     * @param annotations the annotation types requested to be processed
     * @param roundEnv    environment for information about the current and prior round
     * @return whether or not the set of annotation types are claimed by this processor
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (!annotations.isEmpty()) {
            final Set<TypeElement> typeElements = roundEnv.getElementsAnnotatedWith(Patterns.class).stream()
                    .filter(element -> element instanceof TypeElement)
                    .map(element -> (TypeElement) element)
                    .collect(Collectors.toSet());
            if (!typeElements.isEmpty()) {
                final Set<ClassModel> classModels = transform(typeElements);
                if (!classModels.isEmpty()) {
                    generate(classModels);
                }
            }
        }
        return true;
    }

    // Verify correct usage of annotations @Patterns and @Unapply
    private Set<ClassModel> transform(Set<TypeElement> typeElements) {
        final Set<ClassModel> classModels = new HashSet<>();
        final javax.lang.model.util.Elements elementUtils = processingEnv.getElementUtils();
        final Messager messager = processingEnv.getMessager();
        for (TypeElement typeElement : typeElements) {
            final ClassModel classModel = ClassModel.of(elementUtils, typeElement);
            final List<MethodModel> methodModels = classModel.getMethods().stream()
                    .filter(method -> method.isAnnotatedWith(Unapply.class))
                    .collect(toList());
            if (methodModels.isEmpty()) {
                messager.printMessage(Diagnostic.Kind.WARNING, "No @Unapply methods found.", classModel.typeElement());
            } else {
                final boolean methodsValid = methodModels.stream().reduce(true, (bool, method) -> bool && UnapplyChecker.isValid(method.getExecutableElement(), messager), (b1, b2) -> b1 && b2);
                if (methodsValid) {
                    classModels.add(classModel);
                }
            }
        }
        return classModels;
    }

    // Expands all @Patterns classes
    private void generate(Set<ClassModel> classModels) {
        final Filer filer = processingEnv.getFiler();
        for (ClassModel classModel : classModels) {
            final String derivedClassName = deriveClassName(classModel);
            final String code = Generator.generate(derivedClassName, classModel);
            final String fqn = (classModel.hasDefaultPackage() ? "" : classModel.getPackageName() + ".") + derivedClassName;
            try (final Writer writer = filer.createSourceFile(fqn, classModel.typeElement()).openWriter()) {
                writer.write(code);
            } catch (IOException x) {
                throw new Error("Error writing " + fqn, x);
            }
        }
    }

    private String deriveClassName(ClassModel classModel) {
        final String className = classModel.getClassName().replaceAll("\\.", "");
        return ("$".equals(className) ? "" : className) + Patterns.class.getSimpleName();
    }
}