package com.github.uncomplexco.sidekick.application.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.reflect.ToolFromCallable
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.valueParameters

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolArgumentName(val value: String)

fun ToolSet.asLlmTools(): List<Tool<*, *>> =
    asTools().map { tool ->
        val names =
            tool.callable.valueParameters
                .mapNotNull { parameter ->
                    parameter.findAnnotation<ToolArgumentName>()?.let {
                        parameter.name!! to it.value
                    }
                }
                .toMap()
        if (names.isEmpty()) tool else NamedArgumentsTool(tool, names)
    }

private class NamedArgumentsTool<R>(
    private val delegate: ToolFromCallable<R>,
    private val names: Map<String, String>,
) :
    Tool<ToolFromCallable.Args, R>(
        argsType = delegate.argsType,
        resultType = delegate.resultType,
        descriptor =
            delegate.descriptor.copy(
                requiredParameters =
                    delegate.descriptor.requiredParameters.map {
                        it.copy(name = names[it.name] ?: it.name)
                    },
                optionalParameters =
                    delegate.descriptor.optionalParameters.map {
                        it.copy(name = names[it.name] ?: it.name)
                    },
            ),
    ) {
    override fun decodeArgs(
        rawArgs: JSONObject,
        serializer: JSONSerializer,
    ): ToolFromCallable.Args {
        val parameters =
            delegate.callable.valueParameters
                .mapNotNull { parameter ->
                    val name = parameter.name!!
                    rawArgs.entries[names[name] ?: name]?.let { name to it }
                }
                .toMap()
        return delegate.decodeArgs(JSONObject(parameters), serializer)
    }

    override fun encodeArgs(args: ToolFromCallable.Args, serializer: JSONSerializer): JSONObject =
        JSONObject(
            delegate.encodeArgs(args, serializer).entries.mapKeys { (name, _) ->
                names[name] ?: name
            }
        )

    override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): R =
        delegate.decodeResult(rawResult, serializer)

    override fun encodeResult(result: R, serializer: JSONSerializer): JSONElement =
        delegate.encodeResult(result, serializer)

    override suspend fun execute(args: ToolFromCallable.Args): R = delegate.execute(args)
}
