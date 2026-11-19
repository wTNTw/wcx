package com.Johnny.wcx.features.core

class FeatureDescriptor(
    val id: String,
    val name: String,
    val categories: List<String>,
    val description: String,
    val isApi: Boolean,
    val defaultEnabled: Boolean,
    val hasDexResolver: Boolean,
    private val factory: () -> BaseFeature,
) {
    fun instance(): BaseFeature = FeatureRegistry.instance(this, factory)
}
