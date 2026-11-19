package com.Johnny.wcx.features

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

private const val PACKAGE_NAME = "com.Johnny.wcx"
private const val HOOKS_CORE_PACKAGE = "$PACKAGE_NAME.features.core"
private const val SWITCH_FEATURE = "$HOOKS_CORE_PACKAGE.SwitchFeature"
private const val CLICKABLE_FEATURE = "$HOOKS_CORE_PACKAGE.ClickableFeature"
private const val API_FEATURE = "$HOOKS_CORE_PACKAGE.ApiFeature"
private const val RESOLVE_DEX = "$PACKAGE_NAME.dexkit.abc.IResolveDex"

class FeaturesKspProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return FeaturesScanner(environment.codeGenerator, environment.logger)
    }
}

class FeaturesScanner(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private var generated = false

    @OptIn(KspExperimental::class)
    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()
        generated = true

        val symbols = resolver
            .getSymbolsWithAnnotation("$HOOKS_CORE_PACKAGE.Feature")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        if (symbols.isEmpty()) return emptyList()

        symbols.forEach { symbol ->
            if (symbol.classKind != ClassKind.OBJECT) {
                logger.error(
                    "${symbol.qualifiedName?.asString()} is annotated with @Feature but is not an `object`. " +
                        "Only Kotlin objects are supported.",
                    symbol,
                )
            }
        }

        val sortedSymbols = symbols.sortedWith(
            compareBy(
                { symbol ->
                    when {
                        symbol.inheritsFrom(SWITCH_FEATURE) -> 0
                        symbol.inheritsFrom(CLICKABLE_FEATURE) -> 1
                        else -> 2
                    }
                },
                { symbol -> symbol.featureString("name", 0) },
            ),
        )

        val baseFeatureClass = ClassName(HOOKS_CORE_PACKAGE, "BaseFeature")
        val descriptorClass = ClassName(HOOKS_CORE_PACKAGE, "FeatureDescriptor")
        val listClass = ClassName("kotlin.collections", "List")
        val descriptorListType = listClass.parameterizedBy(descriptorClass)
        val featureListType = listClass.parameterizedBy(baseFeatureClass)

        val descriptorsInitializer = CodeBlock.builder().apply {
            addStatement("listOf(")
            indent()
            for (symbol in sortedSymbols) {
                val annotation = symbol.featureAnnotation()
                val categories = annotation.arguments
                    .find { it.name?.asString() == "categories" }
                    ?.value as? List<*> ?: emptyList<Any?>()
                val categoriesCode = stringListCode(categories.mapNotNull { it as? String })
                val defaultEnabled = annotation.arguments
                    .find { it.name?.asString() == "defaultEnabled" }
                    ?.value as? Boolean ?: false

                addStatement(
                    "%T(id = %S, name = %S, categories = %L, description = %S, " +
                        "isApi = %L, defaultEnabled = %L, hasDexResolver = %L, factory = { %T }),",
                    descriptorClass,
                    symbol.qualifiedName?.asString().orEmpty(),
                    symbol.featureString("name", 0),
                    categoriesCode,
                    symbol.featureString("description", 2),
                    symbol.inheritsFrom(API_FEATURE),
                    defaultEnabled,
                    symbol.inheritsFrom(RESOLVE_DEX),
                    symbol.toClassName(),
                )
            }
            unindent()
            add(")")
        }.build()

        val descriptorsProperty = PropertySpec.builder("ALL_FEATURES", descriptorListType)
            .initializer(descriptorsInitializer)
            .build()
        val hookItemsProperty = PropertySpec.builder("ALL_HOOK_ITEMS", featureListType)
            .getter(
                FunSpec.getterBuilder()
                    .addStatement("return ALL_FEATURES.map(%T::instance)", descriptorClass)
                    .build(),
            )
            .build()

        val objectSpec = TypeSpec.objectBuilder("FeaturesProvider")
            .addProperty(descriptorsProperty)
            .addProperty(hookItemsProperty)
            .addKdoc(
                "Auto-generated by [${PACKAGE_NAME}.features.FeaturesScanner]. Do not edit manually.\n" +
                    "Contains lazy descriptors for all classes annotated with [%T].\n",
                ClassName(HOOKS_CORE_PACKAGE, "Feature"),
            )
            .build()

        val dependencies = Dependencies(
            aggregating = true,
            *symbols.mapNotNull { it.containingFile }.toTypedArray(),
        )

        FileSpec.builder(HOOKS_CORE_PACKAGE, "FeaturesProvider")
            .addType(objectSpec)
            .build()
            .writeTo(codeGenerator, dependencies)

        return emptyList()
    }
}

private fun KSClassDeclaration.featureAnnotation() =
    annotations.first { it.shortName.asString() == "Feature" }

private fun KSClassDeclaration.featureString(name: String, fallbackIndex: Int): String {
    val annotation = featureAnnotation()
    return (annotation.arguments.find { it.name?.asString() == name }
        ?: annotation.arguments.getOrNull(fallbackIndex))?.value as? String ?: ""
}

private fun KSClassDeclaration.inheritsFrom(
    qualifiedName: String,
    visited: MutableSet<String> = mutableSetOf(),
): Boolean {
    val ownName = this.qualifiedName?.asString()
    if (ownName == qualifiedName) return true
    if (ownName != null && !visited.add(ownName)) return false
    return superTypes.any { type ->
        val declaration = runCatching { type.resolve().declaration }.getOrNull()
            as? KSClassDeclaration ?: return@any false
        declaration.inheritsFrom(qualifiedName, visited)
    }
}

private fun stringListCode(values: List<String>): CodeBlock = CodeBlock.builder().apply {
    add("listOf(")
    values.forEachIndexed { index, value ->
        if (index > 0) add(", ")
        add("%S", value)
    }
    add(")")
}.build()
