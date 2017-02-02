/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.support.room.solver

import com.android.support.room.Entity
import com.android.support.room.ext.LifecyclesTypeNames
import com.android.support.room.ext.hasAnnotation
import com.android.support.room.parser.ParsedQuery
import com.android.support.room.parser.SQLTypeAffinity
import com.android.support.room.processor.Context
import com.android.support.room.processor.EntityProcessor
import com.android.support.room.processor.FieldProcessor
import com.android.support.room.processor.PojoProcessor
import com.android.support.room.solver.query.parameter.ArrayQueryParameterAdapter
import com.android.support.room.solver.query.parameter.BasicQueryParameterAdapter
import com.android.support.room.solver.query.parameter.CollectionQueryParameterAdapter
import com.android.support.room.solver.query.parameter.QueryParameterAdapter
import com.android.support.room.solver.query.result.ArrayQueryResultAdapter
import com.android.support.room.solver.query.result.EntityRowAdapter
import com.android.support.room.solver.query.result.InstantQueryResultBinder
import com.android.support.room.solver.query.result.ListQueryResultAdapter
import com.android.support.room.solver.query.result.LiveDataQueryResultBinder
import com.android.support.room.solver.query.result.PojoRowAdapter
import com.android.support.room.solver.query.result.QueryResultAdapter
import com.android.support.room.solver.query.result.QueryResultBinder
import com.android.support.room.solver.query.result.RowAdapter
import com.android.support.room.solver.query.result.SingleColumnRowAdapter
import com.android.support.room.solver.query.result.SingleEntityQueryResultAdapter
import com.android.support.room.solver.types.BoxedBooleanToBoxedIntConverter
import com.android.support.room.solver.types.BoxedPrimitiveColumnTypeAdapter
import com.android.support.room.solver.types.BoxedPrimitiveToStringConverter
import com.android.support.room.solver.types.ByteArrayColumnTypeAdapter
import com.android.support.room.solver.types.ColumnTypeAdapter
import com.android.support.room.solver.types.CompositeAdapter
import com.android.support.room.solver.types.CompositeTypeConverter
import com.android.support.room.solver.types.CursorValueReader
import com.android.support.room.solver.types.NoOpConverter
import com.android.support.room.solver.types.PrimitiveBooleanToIntConverter
import com.android.support.room.solver.types.PrimitiveColumnTypeAdapter
import com.android.support.room.solver.types.PrimitiveToStringConverter
import com.android.support.room.solver.types.StatementValueBinder
import com.android.support.room.solver.types.StringColumnTypeAdapter
import com.android.support.room.solver.types.TypeConverter
import com.google.auto.common.MoreElements
import com.google.auto.common.MoreTypes
import com.google.common.annotations.VisibleForTesting
import java.util.LinkedList
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.Types

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
/**
 * Holds all type adapters and can create on demand composite type adapters to convert a type into a
 * database column.
 */
class TypeAdapterStore(val context: Context, @VisibleForTesting vararg extras: Any) {
    /**
     * first type adapter has the highest priority
     */
    private val columnTypeAdapters: List<ColumnTypeAdapter>
    /**
     * first converter has the highest priority
     */
    private val typeConverters: List<TypeConverter>

    init {
        val adapters = arrayListOf<ColumnTypeAdapter>()
        val converters = arrayListOf<TypeConverter>()

        fun addAny(extra : Any?) {
            when (extra) {
                is TypeConverter -> converters.add(extra)
                is ColumnTypeAdapter -> adapters.add(extra)
                is List<*> -> extra.forEach(::addAny)
                else -> throw IllegalArgumentException("unknown extra")
            }
        }

        extras.forEach(::addAny)
        fun addTypeConverter(converter: TypeConverter) {
            converters.add(converter)
        }

        fun addColumnAdapter(adapter: ColumnTypeAdapter) {
            adapters.add(adapter)
        }

        val primitives = PrimitiveColumnTypeAdapter
                .createPrimitiveAdapters(context.processingEnv)
        primitives.forEach(::addColumnAdapter)
        BoxedPrimitiveColumnTypeAdapter
                .createBoxedPrimitiveAdapters(context.processingEnv, primitives)
                .forEach(::addColumnAdapter)
        addColumnAdapter(StringColumnTypeAdapter(context.processingEnv))
        addColumnAdapter(ByteArrayColumnTypeAdapter(context.processingEnv))
        PrimitiveBooleanToIntConverter.create(context.processingEnv).forEach(::addTypeConverter)
        PrimitiveToStringConverter
                .createPrimitives(context)
                .forEach(::addTypeConverter)
        BoxedPrimitiveToStringConverter
                .createBoxedPrimitives(context)
                .forEach(::addTypeConverter)
        BoxedBooleanToBoxedIntConverter.create(context.processingEnv).forEach(::addTypeConverter)
        columnTypeAdapters = adapters
        typeConverters = converters
    }

    // type mirrors that be converted into columns w/o an extra converter
    private val knownColumnTypeMirrors by lazy {
        columnTypeAdapters.map { it.out }
    }

    /**
     * Searches 1 way to bind a value into a statement.
     */
    fun findStatementValueBinder(input : TypeMirror, affinity: SQLTypeAffinity?)
            : StatementValueBinder? {
        val adapter = findDirectAdapterFor(input, affinity)
        if (adapter != null) {
            return adapter
        }
        val targetTypes = targetTypeMirrorsFor(affinity)
        val binder = findTypeConverter(input, targetTypes) ?: return null
        return CompositeAdapter(input, getAllColumnAdapters(binder.to).first(), binder, null)
    }

    /**
     * Returns which entities targets the given affinity.
     */
    private fun targetTypeMirrorsFor(affinity: SQLTypeAffinity?) : List<TypeMirror> {
        val specifiedTargets = affinity?.getTypeMirrors(context.processingEnv)
        return if(specifiedTargets == null || specifiedTargets.isEmpty()) {
            knownColumnTypeMirrors
        } else {
            specifiedTargets
        }
    }

    /**
     * Searches 1 way to read it from cursor
     */
    fun findCursorValueReader(output: TypeMirror, affinity: SQLTypeAffinity?) : CursorValueReader? {
        val adapter = findColumnTypeAdapter(output, affinity)
        if (adapter != null) {
            // two way is better
            return adapter
        }
        // we could not find a two way version, search for anything
        val targetTypes = targetTypeMirrorsFor(affinity)
        val converter = findTypeConverter(targetTypes, output) ?: return null
        return CompositeAdapter(output,
                getAllColumnAdapters(converter.from).first(), null, converter)
    }

    /**
     * Tries to reverse the converter going through the same nodes, if possible.
     */
    @VisibleForTesting
    fun reverse(converter : TypeConverter) : TypeConverter? {
        return when(converter) {
            is NoOpConverter -> converter
            is CompositeTypeConverter ->  {
                val r1 = reverse(converter.conv1) ?: return null
                val r2 = reverse(converter.conv2) ?: return null
                CompositeTypeConverter(r2, r1)
            }
            else -> {
                val types = context.processingEnv.typeUtils
                typeConverters.firstOrNull {
                    types.isSameType(it.from, converter.to) && types
                            .isSameType(it.to, converter.from)
                }
            }
        }
    }

    /**
     * Finds a two way converter, if you need 1 way, use findStatementValueBinder or
     * findCursorValueReader.
     */
    fun findColumnTypeAdapter(out: TypeMirror, affinity: SQLTypeAffinity?)
            : ColumnTypeAdapter? {
        val adapter = findDirectAdapterFor(out, affinity)
        if (adapter != null) {
            return adapter
        }
        val targetTypes = targetTypeMirrorsFor(affinity)
        val intoStatement = findTypeConverter(out, targetTypes) ?: return null
        // ok found a converter, try the reverse now
        val fromCursor = reverse(intoStatement) ?: findTypeConverter(intoStatement.to, out)
                ?: return null
        return CompositeAdapter(out, getAllColumnAdapters(intoStatement.to).first(), intoStatement,
                fromCursor)
    }

    private fun findDirectAdapterFor(out: TypeMirror, affinity: SQLTypeAffinity?)
            : ColumnTypeAdapter? {
        val adapter = getAllColumnAdapters(out).firstOrNull {
            affinity == null || it.typeAffinity == affinity
        }
        return adapter
    }

    fun findTypeConverter(input: TypeMirror, output: TypeMirror): TypeConverter? {
        return findTypeConverter(listOf(input), listOf(output))
    }

    private fun isLiveData(declared: DeclaredType): Boolean {
        val typeElement = MoreElements.asType(declared.asElement())
        val qName = typeElement.qualifiedName.toString()
        // even though computable live data is internal, we still check for it as we may inherit
        // it from some internal class.
        return qName == LifecyclesTypeNames.COMPUTABLE_LIVE_DATA.toString() ||
                qName == LifecyclesTypeNames.LIVE_DATA.toString()
    }

    fun findQueryResultBinder(typeMirror: TypeMirror, query: ParsedQuery): QueryResultBinder {
        return if (typeMirror.kind == TypeKind.DECLARED) {
            val declared = MoreTypes.asDeclared(typeMirror)
            if (declared.typeArguments.isEmpty()) {
                InstantQueryResultBinder(findQueryResultAdapter(typeMirror, query))
            } else {
                if (isLiveData(declared)) {
                    val liveDataTypeArg = declared.typeArguments.first()
                    LiveDataQueryResultBinder(liveDataTypeArg, query.tables,
                            findQueryResultAdapter(liveDataTypeArg, query))
                } else {
                    InstantQueryResultBinder(findQueryResultAdapter(typeMirror, query))
                }
            }
        } else {
            InstantQueryResultBinder(findQueryResultAdapter(typeMirror, query))
        }
    }

    private fun findQueryResultAdapter(typeMirror: TypeMirror, query: ParsedQuery)
            : QueryResultAdapter? {
        if (typeMirror.kind == TypeKind.DECLARED) {
            val declared = MoreTypes.asDeclared(typeMirror)
            if (declared.typeArguments.isEmpty()) {
                val rowAdapter = findRowAdapter(typeMirror, query) ?: return null
                return SingleEntityQueryResultAdapter(rowAdapter)
            }
            if (MoreTypes.isTypeOf(java.util.List::class.java, typeMirror)) {
                val typeArg = declared.typeArguments.first()
                val rowAdapter = findRowAdapter(typeArg, query) ?: return null
                return ListQueryResultAdapter(rowAdapter)
            }
            return null
        } else if (typeMirror.kind == TypeKind.ARRAY) {
            val array = MoreTypes.asArray(typeMirror)
            val rowAdapter =
                    findRowAdapter(array.componentType, query) ?: return null
            return ArrayQueryResultAdapter(rowAdapter)
        } else {
            val rowAdapter = findRowAdapter(typeMirror, query) ?: return null
            return SingleEntityQueryResultAdapter(rowAdapter)
        }
    }

    /**
     * Find a converter from cursor to the given type mirror.
     * If there is information about the query result, we try to use it to accept *any* POJO.
     */
    @VisibleForTesting
    fun findRowAdapter(typeMirror: TypeMirror, query: ParsedQuery): RowAdapter? {
        if (typeMirror.kind == TypeKind.DECLARED) {
            val declared = MoreTypes.asDeclared(typeMirror)
            if (declared.typeArguments.isNotEmpty()) {
                // TODO one day support this
                return null
            }
            val asElement = MoreTypes.asElement(typeMirror)
            if (asElement.hasAnnotation(Entity::class)) {
                // TODO we might parse this too much, would be nice to scope these parsers
                // at least for entities.
                return EntityRowAdapter(EntityProcessor(context,
                        MoreElements.asType(asElement)).process())
            }
            // if result is unknown, we are fine w/ single column result
            val resultInfo = query.resultInfo
            if ((resultInfo?.columns?.size ?: 1) == 1) {
                val singleColumn = findColumnTypeAdapter(typeMirror,
                        resultInfo?.columns?.get(0)?.type)
                if (singleColumn != null) {
                    return SingleColumnRowAdapter(singleColumn)
                }
            }
            // try to map the result only if the query is valid
            if (resultInfo != null && resultInfo.error == null) {
                val pojo = PojoProcessor(
                        baseContext = context,
                        element = MoreTypes.asTypeElement(typeMirror),
                        bindingScope = FieldProcessor.BindingScope.READ_FROM_CURSOR
                ).process()
                return PojoRowAdapter(
                        context = context,
                        info = resultInfo,
                        pojo = pojo,
                        out = typeMirror)
            }
            return null
        } else {
            val singleColumn = findColumnTypeAdapter(typeMirror, null) ?: return null
            return SingleColumnRowAdapter(singleColumn)
        }
    }

    fun findQueryParameterAdapter(typeMirror: TypeMirror): QueryParameterAdapter? {
        if (MoreTypes.isType(typeMirror)
                && (MoreTypes.isTypeOf(java.util.List::class.java, typeMirror)
                || MoreTypes.isTypeOf(java.util.Set::class.java, typeMirror))) {
            val declared = MoreTypes.asDeclared(typeMirror)
            val binder = findStatementValueBinder(declared.typeArguments.first(),
                    null) ?: return null
            return CollectionQueryParameterAdapter(binder)
        } else if (typeMirror is ArrayType) {
            val component = typeMirror.componentType
            val binder = findStatementValueBinder(component, null) ?: return null
            return ArrayQueryParameterAdapter(binder)
        } else {
            val binder = findStatementValueBinder(typeMirror, null) ?: return null
            return BasicQueryParameterAdapter(binder)
        }
    }

    private fun findTypeConverter(input: TypeMirror, outputs: List<TypeMirror>): TypeConverter? {
        return findTypeConverter(listOf(input), outputs)
    }

    private fun findTypeConverter(input: List<TypeMirror>, output : TypeMirror): TypeConverter? {
        return findTypeConverter(input, listOf(output))
    }

    private fun findTypeConverter(inputs: List<TypeMirror>, outputs: List<TypeMirror>)
            : TypeConverter? {
        if (inputs.isEmpty()) {
            return null
        }
        val types = context.processingEnv.typeUtils
        inputs.forEach { input ->
            if (outputs.any { output -> types.isSameType(input, output) }) {
                return NoOpConverter(input)
            }
        }

        val excludes = arrayListOf<TypeMirror>()

        val queue = LinkedList<TypeConverter>()
        fun exactMatch(candidates: List<TypeConverter>, outputs: List<TypeMirror>, types: Types)
                : TypeConverter? {
            return candidates.firstOrNull {
                outputs.any { output -> types.isSameType(output, it.to) }
            }
        }
        inputs.forEach { input ->
            val candidates = getAllTypeConverters(input, excludes)
            val match = exactMatch(candidates, outputs, types)
            if (match != null) {
                return match
            }
            candidates.forEach {
                excludes.add(it.to)
                queue.add(it)
            }
        }
        excludes.addAll(inputs)
        while (queue.isNotEmpty()) {
            val prev = queue.pop()
            val from = prev.to
            val candidates = getAllTypeConverters(from, excludes)
            val match = exactMatch(candidates, outputs, types)
            if (match != null) {
                return CompositeTypeConverter(prev, match)
            }
            candidates.forEach {
                excludes.add(it.to)
                queue.add(CompositeTypeConverter(prev, it))
            }
        }
        return null
    }

    private fun getAllColumnAdapters(input: TypeMirror): List<ColumnTypeAdapter> {
        return columnTypeAdapters.filter {
            context.processingEnv.typeUtils.isSameType(input, it.out)
        }
    }

    private fun getAllTypeConverters(input: TypeMirror, excludes: List<TypeMirror>):
            List<TypeConverter> {
        val types = context.processingEnv.typeUtils
        return typeConverters.filter { converter ->
            types.isSameType(input, converter.from) &&
                    !excludes.any { types.isSameType(it, converter.to) }
        }
    }
}
