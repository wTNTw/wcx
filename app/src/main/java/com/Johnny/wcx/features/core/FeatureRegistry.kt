package com.Johnny.wcx.features.core

import java.util.concurrent.ConcurrentHashMap

object FeatureRegistry {
    private val instances = ConcurrentHashMap<String, BaseFeature>()

    internal fun instance(
        descriptor: FeatureDescriptor,
        factory: () -> BaseFeature,
    ): BaseFeature = instances.computeIfAbsent(descriptor.id) {
        factory().apply {
            technicalId = descriptor.id
            name = descriptor.name
            categories = descriptor.categories
            description = descriptor.description
            startupDefaultEnabled = descriptor.defaultEnabled
        }
    }

    fun instantiated(): List<BaseFeature> = instances.values.toList()
}
