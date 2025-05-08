/*
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.aot;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.QueryHint;
import jakarta.persistence.Tuple;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.jspecify.annotations.Nullable;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.jpa.repository.QueryRewriter;
import org.springframework.data.jpa.repository.query.DeclaredQuery;
import org.springframework.data.jpa.repository.query.JpaQueryMethod;
import org.springframework.data.jpa.repository.query.ParameterBinding;
import org.springframework.data.repository.aot.generate.AotQueryMethodGenerationContext;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.CodeBlock.Builder;
import org.springframework.javapoet.TypeName;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Common code blocks for JPA AOT Fragment generation.
 *
 * @author Christoph Strobl
 * @author Mark Paluch
 * @since 4.0
 */
class JpaCodeBlocks {

	/**
	 * @return new {@link QueryBlockBuilder}.
	 */
	public static QueryBlockBuilder queryBuilder(AotQueryMethodGenerationContext context, JpaQueryMethod queryMethod) {
		return new QueryBlockBuilder(context, queryMethod);
	}

	/**
	 * @return new {@link QueryExecutionBlockBuilder}.
	 */
	static QueryExecutionBlockBuilder executionBuilder(AotQueryMethodGenerationContext context,
			JpaQueryMethod queryMethod) {
		return new QueryExecutionBlockBuilder(context, queryMethod);
	}

	/**
	 * Builder for the actual query code block.
	 */
	static class QueryBlockBuilder {

		private final AotQueryMethodGenerationContext context;
		private final JpaQueryMethod queryMethod;
		private String queryVariableName;
		private @Nullable AotQueries queries;
		private MergedAnnotation<QueryHints> queryHints = MergedAnnotation.missing();
		private @Nullable AotEntityGraph entityGraph;
		private @Nullable String sqlResultSetMapping;
		private @Nullable Class<?> queryReturnType;
		private @Nullable Class<?> queryRewriter = QueryRewriter.IdentityQueryRewriter.class;

		private QueryBlockBuilder(AotQueryMethodGenerationContext context, JpaQueryMethod queryMethod) {
			this.context = context;
			this.queryMethod = queryMethod;
			this.queryVariableName = context.localVariable("query");
		}

		public QueryBlockBuilder usingQueryVariableName(String queryVariableName) {

			this.queryVariableName = context.localVariable(queryVariableName);
			return this;
		}

		public QueryBlockBuilder filter(AotQueries query) {
			this.queries = query;
			return this;
		}

		public QueryBlockBuilder nativeQuery(MergedAnnotation<NativeQuery> nativeQuery) {

			if (nativeQuery.isPresent()) {
				this.sqlResultSetMapping = nativeQuery.getString("sqlResultSetMapping");
			}
			return this;
		}

		public QueryBlockBuilder queryHints(MergedAnnotation<QueryHints> queryHints) {

			this.queryHints = queryHints;
			return this;
		}

		public QueryBlockBuilder entityGraph(@Nullable AotEntityGraph entityGraph) {
			this.entityGraph = entityGraph;
			return this;
		}

		public QueryBlockBuilder queryReturnType(@Nullable Class<?> queryReturnType) {
			this.queryReturnType = queryReturnType;
			return this;
		}

		public QueryBlockBuilder queryRewriter(@Nullable Class<?> queryRewriter) {
			this.queryRewriter = queryRewriter == null ? QueryRewriter.IdentityQueryRewriter.class : queryRewriter;
			return this;
		}

		/**
		 * Build the query block.
		 *
		 * @return
		 */
		public CodeBlock build() {

			Assert.notNull(queries, "Queries must not be null");

			boolean isProjecting = context.getReturnedType().isProjecting();
			Class<?> actualReturnType = isProjecting ? context.getActualReturnType().toClass()
					: context.getRepositoryInformation().getDomainType();

			String dynamicReturnType = null;
			if (queryMethod.getParameters().hasDynamicProjection()) {
				dynamicReturnType = context.getParameterName(queryMethod.getParameters().getDynamicProjectionIndex());
			}

			CodeBlock.Builder builder = CodeBlock.builder();

			String queryStringVariableName = null;
			String queryRewriterName = null;

			if (queries.result() instanceof StringAotQuery && queryRewriter != QueryRewriter.IdentityQueryRewriter.class) {

				queryRewriterName = context.localVariable("queryRewriter");
				builder.addStatement("$T $L = new $T()", queryRewriter, queryRewriterName, queryRewriter);
			}

			if (queries.result() instanceof StringAotQuery sq) {

				queryStringVariableName = "%sString".formatted(queryVariableName);
				builder.add(buildQueryString(sq, queryStringVariableName));
			}

			String countQueryStringNameVariableName = null;
			String countQueryVariableName = context
					.localVariable("count%s".formatted(StringUtils.capitalize(queryVariableName)));

			if (queryMethod.isPageQuery() && queries.count() instanceof StringAotQuery sq) {

				countQueryStringNameVariableName = context
						.localVariable("count%sString".formatted(StringUtils.capitalize(queryVariableName)));
				builder.add(buildQueryString(sq, countQueryStringNameVariableName));
			}

			String sortParameterName = context.getSortParameterName();
			if (sortParameterName == null && context.getPageableParameterName() != null) {
				sortParameterName = "%s.getSort()".formatted(context.getPageableParameterName());
			}

			if ((StringUtils.hasText(sortParameterName) || StringUtils.hasText(dynamicReturnType))
					&& queries != null && queries.result() instanceof StringAotQuery
					&& StringUtils.hasText(queryStringVariableName)) {
				builder.add(applyRewrite(sortParameterName, dynamicReturnType, queryStringVariableName, actualReturnType));
			}

			if (queries.result().hasExpression() || queries.count().hasExpression()) {
				builder.addStatement("class ExpressionMarker{}");
			}

			builder.add(createQuery(false, queryVariableName, queryStringVariableName, queryRewriterName, queries.result(),
					this.sqlResultSetMapping, this.queryHints, this.entityGraph, this.queryReturnType));

			builder.add(applyLimits(queries.result().isExists()));

			if (queryMethod.isPageQuery()) {

				builder.beginControlFlow("$T $L = () ->", LongSupplier.class, context.localVariable("countAll"));

				boolean queryHints = this.queryHints.isPresent() && this.queryHints.getBoolean("forCounting");

				builder.add(createQuery(true, countQueryVariableName, countQueryStringNameVariableName, queryRewriterName,
						queries.count(), null,
						queryHints ? this.queryHints : MergedAnnotation.missing(), null, Long.class));
				builder.addStatement("return ($T) $L.getSingleResult()", Long.class, countQueryVariableName);

				// end control flow does not work well with lambdas
				builder.unindent();
				builder.add("};\n");
			}

			return builder.build();
		}

		private CodeBlock buildQueryString(StringAotQuery sq, String queryStringVariableName) {

			CodeBlock.Builder builder = CodeBlock.builder();
			builder.addStatement("$T $L = $S", String.class, queryStringVariableName, sq.getQueryString());
			return builder.build();
		}

		private CodeBlock applyRewrite(@Nullable String sort, @Nullable String dynamicReturnType, String queryString,
				Class<?> actualReturnType) {

			Builder builder = CodeBlock.builder();

			boolean hasSort = StringUtils.hasText(sort);
			if (hasSort) {
				builder.beginControlFlow("if ($L.isSorted())", sort);
			}

			builder.addStatement("$T $L = $T.$L($L)", DeclaredQuery.class, context.localVariable("declaredQuery"),
					DeclaredQuery.class,
					queries != null && queries.isNative() ? "nativeQuery" : "jpqlQuery", queryString);

			boolean hasDynamicReturnType = StringUtils.hasText(dynamicReturnType);

			if (hasSort && hasDynamicReturnType) {
				builder.addStatement("$L = rewriteQuery($L, $L, $L)", queryString, context.localVariable("declaredQuery"), sort,
						dynamicReturnType);
			} else if (hasSort) {
				builder.addStatement("$L = rewriteQuery($L, $L, $T.class)", queryString, context.localVariable("declaredQuery"),
						sort, actualReturnType);
			} else if (hasDynamicReturnType) {
				builder.addStatement("$L = rewriteQuery($L, $T.unsorted(), $L)", context.localVariable("declaredQuery"),
						queryString, Sort.class,
						dynamicReturnType);
			}

			if (hasSort) {
				builder.endControlFlow();
			}

			return builder.build();
		}

		private CodeBlock applyLimits(boolean exists) {

			Builder builder = CodeBlock.builder();

			if (exists) {
				builder.addStatement("$L.setMaxResults(1)", queryVariableName);

				return builder.build();
			}

			String limit = context.getLimitParameterName();

			if (StringUtils.hasText(limit)) {
				builder.beginControlFlow("if ($L.isLimited())", limit);
				builder.addStatement("$L.setMaxResults($L.max())", queryVariableName, limit);
				builder.endControlFlow();
			} else if (queries != null && queries.result().isLimited()) {
				builder.addStatement("$L.setMaxResults($L)", queryVariableName, queries.result().getLimit().max());
			}

			String pageable = context.getPageableParameterName();

			if (StringUtils.hasText(pageable)) {

				builder.beginControlFlow("if ($L.isPaged())", pageable);
				builder.addStatement("$L.setFirstResult(Long.valueOf($L.getOffset()).intValue())", queryVariableName, pageable);
				if (queryMethod.isSliceQuery()) {
					builder.addStatement("$L.setMaxResults($L.getPageSize() + 1)", queryVariableName, pageable);
				} else {
					builder.addStatement("$L.setMaxResults($L.getPageSize())", queryVariableName, pageable);
				}
				builder.endControlFlow();
			}

			return builder.build();
		}

		private CodeBlock createQuery(boolean count, String queryVariableName, @Nullable String queryStringNameVariableName,
				@Nullable String queryRewriterName, AotQuery query, @Nullable String sqlResultSetMapping,
				MergedAnnotation<QueryHints> queryHints,
				@Nullable AotEntityGraph entityGraph, @Nullable Class<?> queryReturnType) {

			Builder builder = CodeBlock.builder();

			builder.add(doCreateQuery(count, queryVariableName, queryStringNameVariableName, queryRewriterName, query,
					sqlResultSetMapping,
					queryReturnType));

			if (entityGraph != null) {
				builder.add(applyEntityGraph(entityGraph, queryVariableName));
			}

			if (queryHints.isPresent()) {
				builder.add(applyHints(queryVariableName, queryHints));
				builder.add("\n");
			}

			for (ParameterBinding binding : query.getParameterBindings()) {

				Object prepare = binding.prepare("s");
				Object parameterIdentifier = getParameterName(binding.getIdentifier());
				String valueFormat = parameterIdentifier instanceof CharSequence ? "$S" : "$L";

				if (prepare instanceof String prepared && !prepared.equals("s")) {

					String format = prepared.replaceAll("%", "%%").replace("s", "%s");
					builder.addStatement("$L.setParameter(%s, $S.formatted($L))".formatted(valueFormat), queryVariableName,
							parameterIdentifier, format, getParameter(binding.getOrigin()));
				} else {
					builder.addStatement("$L.setParameter(%s, $L)".formatted(valueFormat), queryVariableName, parameterIdentifier,
							getParameter(binding.getOrigin()));
				}
			}

			return builder.build();
		}

		private CodeBlock doCreateQuery(boolean count, String queryVariableName,
				@Nullable String queryStringName, @Nullable String queryRewriterName, AotQuery query,
				@Nullable String sqlResultSetMapping,
				@Nullable Class<?> queryReturnType) {

			ReturnedType returnedType = context.getReturnedType();
			Builder builder = CodeBlock.builder();
			String queryStringNameToUse = queryStringName;

			if (query instanceof StringAotQuery sq) {

				if (StringUtils.hasText(queryRewriterName)) {

					queryStringNameToUse = queryStringName + "Rewritten";

					if (StringUtils.hasText(context.getPageableParameterName())) {
						builder.addStatement("$T $L = $L.rewrite($L, $L)", String.class, queryStringNameToUse, queryRewriterName,
								queryStringName, context.getPageableParameterName());
					} else if (StringUtils.hasText(context.getSortParameterName())) {
						builder.addStatement("$T $L = $L.rewrite($L, $L)", String.class, queryStringNameToUse, queryRewriterName,
								queryStringName, context.getSortParameterName());
					} else {
						builder.addStatement("$T $L = $L.rewrite($L, $T.unsorted())", String.class, queryStringNameToUse,
								queryRewriterName, queryStringName, Sort.class);
					}
				}

				if (StringUtils.hasText(sqlResultSetMapping)) {

					builder.addStatement("$T $L = this.$L.createNativeQuery($L, $S)", Query.class, queryVariableName,
							context.fieldNameOf(EntityManager.class), queryStringNameToUse, sqlResultSetMapping);

					return builder.build();
				}

				if (query.isNative()) {

					if (queryReturnType != null) {

						builder.addStatement("$T $L = this.$L.createNativeQuery($L, $T.class)", Query.class, queryVariableName,
								context.fieldNameOf(EntityManager.class), queryStringNameToUse, queryReturnType);
					} else {
						builder.addStatement("$T $L = this.$L.createNativeQuery($L)", Query.class, queryVariableName,
								context.fieldNameOf(EntityManager.class), queryStringNameToUse);
					}

					return builder.build();
				}

				if (sq.hasConstructorExpressionOrDefaultProjection() && !count && returnedType.isProjecting()
						&& returnedType.getReturnedType().isInterface()) {
					builder.addStatement("$T $L = this.$L.createQuery($L)", Query.class, queryVariableName,
							context.fieldNameOf(EntityManager.class), queryStringNameToUse);
				} else {

					String createQueryMethod = query.isNative() ? "createNativeQuery" : "createQuery";

					if (!sq.hasConstructorExpressionOrDefaultProjection() && !count && returnedType.isProjecting()
							&& returnedType.getReturnedType().isInterface()) {
						builder.addStatement("$T $L = this.$L.$L($L, $T.class)", Query.class, queryVariableName,
								context.fieldNameOf(EntityManager.class), createQueryMethod, queryStringNameToUse, Tuple.class);
					} else {
						builder.addStatement("$T $L = this.$L.$L($L)", Query.class, queryVariableName,
								context.fieldNameOf(EntityManager.class), createQueryMethod, queryStringNameToUse);
					}
				}

				return builder.build();
			}

			if (query instanceof NamedAotQuery nq) {

				if (!count && returnedType.isProjecting() && returnedType.getReturnedType().isInterface()) {
					builder.addStatement("$T $L = this.$L.createNamedQuery($S)", Query.class, queryVariableName,
							context.fieldNameOf(EntityManager.class), nq.getName());
					return builder.build();
				} else if (queryReturnType != null) {

					builder.addStatement("$T $L = this.$L.createNamedQuery($S, $T.class)", Query.class, queryVariableName,
							context.fieldNameOf(EntityManager.class), nq.getName(), queryReturnType);

					return builder.build();
				}

				builder.addStatement("$T $L = this.$L.createNamedQuery($S)", Query.class, queryVariableName,
						context.fieldNameOf(EntityManager.class), nq.getName());

				return builder.build();
			}

			throw new UnsupportedOperationException("Unsupported query type: " + query);
		}

		private Object getParameterName(ParameterBinding.BindingIdentifier identifier) {
			return identifier.hasName() ? identifier.getName() : Integer.valueOf(identifier.getPosition());
		}

		private Object getParameter(ParameterBinding.ParameterOrigin origin) {

			if (origin.isMethodArgument() && origin instanceof ParameterBinding.MethodInvocationArgument mia) {

				if (mia.identifier().hasPosition()) {
					return context.getRequiredBindableParameterName(mia.identifier().getPosition() - 1);
				}

				if (mia.identifier().hasName()) {
					return context.getRequiredBindableParameterName(mia.identifier().getName());
				}
			}

			if (origin.isExpression() && origin instanceof ParameterBinding.Expression expr) {

				Builder builder = CodeBlock.builder();
				ParameterNameDiscoverer discoverer = new DefaultParameterNameDiscoverer();
				var parameterNames = discoverer.getParameterNames(context.getMethod());

				String expressionString = expr.expression().getExpressionString();
				// re-wrap expression
				if (!expressionString.startsWith("$")) {
					expressionString = "#{" + expressionString + "}";
				}

				builder.add("evaluateExpression(ExpressionMarker.class.getEnclosingMethod(), $S, $L)", expressionString,
						StringUtils.arrayToCommaDelimitedString(parameterNames));

				return builder.build();
			}

			throw new UnsupportedOperationException("Not supported yet");
		}

		private CodeBlock applyEntityGraph(AotEntityGraph entityGraph, String queryVariableName) {

			CodeBlock.Builder builder = CodeBlock.builder();

			if (StringUtils.hasText(entityGraph.name())) {

				builder.addStatement("$T<?> $L = $L.getEntityGraph($S)", jakarta.persistence.EntityGraph.class,
						context.localVariable("entityGraph"),
						context.fieldNameOf(EntityManager.class), entityGraph.name());
			} else {

				builder.addStatement("$T<$T> $L = $L.createEntityGraph($T.class)",
						jakarta.persistence.EntityGraph.class, context.getActualReturnType().getType(),
						context.localVariable("entityGraph"),
						context.fieldNameOf(EntityManager.class), context.getActualReturnType().getType());

				for (String attributePath : entityGraph.attributePaths()) {

					String[] pathComponents = StringUtils.delimitedListToStringArray(attributePath, ".");

					StringBuilder chain = new StringBuilder(context.localVariable("entityGraph"));
					for (int i = 0; i < pathComponents.length; i++) {

						if (i < pathComponents.length - 1) {
							chain.append(".addSubgraph($S)");
						} else {
							chain.append(".addAttributeNodes($S)");
						}
					}

					builder.addStatement(chain.toString(), (Object[]) pathComponents);
				}

				builder.addStatement("$L.setHint($S, $L)", queryVariableName, entityGraph.type().getKey(),
						context.localVariable("entityGraph"));
			}

			return builder.build();
		}

		private CodeBlock applyHints(String queryVariableName, MergedAnnotation<QueryHints> queryHints) {

			Builder hintsBuilder = CodeBlock.builder();
			MergedAnnotation<QueryHint>[] values = queryHints.getAnnotationArray("value", QueryHint.class);

			for (MergedAnnotation<QueryHint> hint : values) {
				hintsBuilder.addStatement("$L.setHint($S, $S)", queryVariableName, hint.getString("name"),
						hint.getString("value"));
			}

			return hintsBuilder.build();
		}

	}

	static class QueryExecutionBlockBuilder {

		private final AotQueryMethodGenerationContext context;
		private final JpaQueryMethod queryMethod;
		private @Nullable AotQuery aotQuery;
		private String queryVariableName;
		private MergedAnnotation<Modifying> modifying = MergedAnnotation.missing();

		private QueryExecutionBlockBuilder(AotQueryMethodGenerationContext context, JpaQueryMethod queryMethod) {

			this.context = context;
			this.queryMethod = queryMethod;
			this.queryVariableName = context.localVariable("query");
		}

		public QueryExecutionBlockBuilder referencing(String queryVariableName) {

			this.queryVariableName = context.localVariable(queryVariableName);
			return this;
		}

		public QueryExecutionBlockBuilder query(AotQuery aotQuery) {

			this.aotQuery = aotQuery;
			return this;
		}

		public QueryExecutionBlockBuilder modifying(MergedAnnotation<Modifying> modifying) {

			this.modifying = modifying;
			return this;
		}

		public CodeBlock build() {

			Builder builder = CodeBlock.builder();

			boolean isProjecting = context.getActualReturnType() != null
					&& !ObjectUtils.nullSafeEquals(TypeName.get(context.getRepositoryInformation().getDomainType()),
							context.getActualReturnType());
			Type actualReturnType = isProjecting ? context.getActualReturnType().getType()
					: context.getRepositoryInformation().getDomainType();
			builder.add("\n");

			if (modifying.isPresent()) {

				if (modifying.getBoolean("flushAutomatically")) {
					builder.addStatement("this.$L.flush()", context.fieldNameOf(EntityManager.class));
				}

				Class<?> returnType = context.getMethod().getReturnType();

				if (returnsModifying(returnType)) {
					builder.addStatement("int $L = $L.executeUpdate()", context.localVariable("result"), queryVariableName);
				} else {
					builder.addStatement("$L.executeUpdate()", queryVariableName);
				}

				if (modifying.getBoolean("clearAutomatically")) {
					builder.addStatement("this.$L.clear()", context.fieldNameOf(EntityManager.class));
				}

				if (returnType == int.class || returnType == long.class || returnType == Integer.class) {
					builder.addStatement("return $L", context.localVariable("result"));
				}

				if (returnType == Long.class) {
					builder.addStatement("return (long) $L", context.localVariable("result"));
				}

				return builder.build();
			}

			if (aotQuery != null && aotQuery.isDelete()) {

				builder.addStatement("$T<$T> $L = $L.getResultList()", List.class, actualReturnType,
						context.localVariable("resultList"), queryVariableName);
				builder.addStatement("$L.forEach($L::remove)", context.localVariable("resultList"),
						context.fieldNameOf(EntityManager.class));
				if (!context.getReturnType().isAssignableFrom(List.class)) {
					if (ClassUtils.isAssignable(Number.class, context.getMethod().getReturnType())) {
						builder.addStatement("return $T.valueOf($L.size())", context.getMethod().getReturnType(),
								context.localVariable("resultList"));
					} else {
						builder.addStatement("return $L.isEmpty() ? null : $L.iterator().next()",
								context.localVariable("resultList"), context.localVariable("resultList"));
					}
				} else {
					builder.addStatement("return $L", context.localVariable("resultList"));
				}
			} else if (aotQuery != null && aotQuery.isExists()) {
				builder.addStatement("return !$L.getResultList().isEmpty()", queryVariableName);
			} else if (aotQuery != null) {

				if (context.getReturnedType().isProjecting()) {

					TypeName queryResultType = TypeName.get(context.getActualReturnType().toClass());

					if (queryMethod.isCollectionQuery()) {
						builder.addStatement("return ($T) convertMany($L.getResultList(), $L, $T.class)",
								context.getReturnTypeName(), queryVariableName, aotQuery.isNative(), queryResultType);
					} else if (queryMethod.isStreamQuery()) {
						builder.addStatement("return ($T) convertMany($L.getResultStream(), $L, $T.class)",
								context.getReturnTypeName(), queryVariableName, aotQuery.isNative(), queryResultType);
					} else if (queryMethod.isPageQuery()) {
						builder.addStatement(
								"return $T.getPage(($T<$T>) convertMany($L.getResultList(), $L, $T.class), $L, $L)",
								PageableExecutionUtils.class, List.class, actualReturnType, queryVariableName, aotQuery.isNative(),
								queryResultType, context.getPageableParameterName(), context.localVariable("countAll"));
					} else if (queryMethod.isSliceQuery()) {
						builder.addStatement("$T<$T> $L = ($T<$T>) convertMany($L.getResultList(), $L, $T.class)", List.class,
								actualReturnType, context.localVariable("resultList"), List.class, actualReturnType, queryVariableName,
								aotQuery.isNative(),
								queryResultType);
						builder.addStatement("boolean $L = $L.isPaged() && $L.size() > $L.getPageSize()",
								context.localVariable("hasNext"), context.getPageableParameterName(),
								context.localVariable("resultList"), context.getPageableParameterName());
						builder.addStatement(
								"return new $T<>($L ? $L.subList(0, $L.getPageSize()) : $L, $L, $L)", SliceImpl.class,
								context.localVariable("hasNext"), context.localVariable("resultList"),
								context.getPageableParameterName(), context.localVariable("resultList"),
								context.getPageableParameterName(), context.localVariable("hasNext"));
					} else {

						if (Optional.class.isAssignableFrom(context.getReturnType().toClass())) {
							builder.addStatement("return $T.ofNullable(($T) convertOne($L.getSingleResultOrNull(), $L, $T.class))",
									Optional.class, actualReturnType, queryVariableName, aotQuery.isNative(), queryResultType);
						} else {
							builder.addStatement("return ($T) convertOne($L.getSingleResultOrNull(), $L, $T.class)",
									context.getReturnTypeName(), queryVariableName, aotQuery.isNative(), queryResultType);
						}
					}

				} else {

					if (queryMethod.isCollectionQuery()) {
						builder.addStatement("return ($T) $L.getResultList()", context.getReturnTypeName(), queryVariableName);
					} else if (queryMethod.isStreamQuery()) {
						builder.addStatement("return ($T) $L.getResultStream()", context.getReturnTypeName(), queryVariableName);
					} else if (queryMethod.isPageQuery()) {
						builder.addStatement("return $T.getPage(($T<$T>) $L.getResultList(), $L, $L)",
								PageableExecutionUtils.class, List.class, actualReturnType, queryVariableName,
								context.getPageableParameterName(), context.localVariable("countAll"));
					} else if (queryMethod.isSliceQuery()) {
						builder.addStatement("$T<$T> $L = $L.getResultList()", List.class, actualReturnType,
								context.localVariable("resultList"), queryVariableName);
						builder.addStatement("boolean $L = $L.isPaged() && $L.size() > $L.getPageSize()",
								context.localVariable("hasNext"), context.getPageableParameterName(),
								context.localVariable("resultList"), context.getPageableParameterName());
						builder.addStatement(
								"return new $T<>($L ? $L.subList(0, $L.getPageSize()) : $L, $L, $L)", SliceImpl.class,
								context.localVariable("hasNext"), context.localVariable("resultList"),
								context.getPageableParameterName(), context.localVariable("resultList"),
								context.getPageableParameterName(), context.localVariable("hasNext"));
					} else {

						if (Optional.class.isAssignableFrom(context.getReturnType().toClass())) {
							builder.addStatement("return $T.ofNullable(($T) $L.getSingleResultOrNull())", Optional.class,
									actualReturnType, queryVariableName);
						} else {
							builder.addStatement("return ($T) $L.getSingleResultOrNull()", context.getReturnTypeName(),
									queryVariableName);
						}
					}
				}
			}

			return builder.build();
		}

		public static boolean returnsModifying(Class<?> returnType) {

			return returnType == int.class || returnType == long.class || returnType == Integer.class
					|| returnType == Long.class;
		}

	}

}
