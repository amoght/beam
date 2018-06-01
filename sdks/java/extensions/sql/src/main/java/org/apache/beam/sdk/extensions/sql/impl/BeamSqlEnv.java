/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.impl;

import java.util.Map;
import org.apache.beam.sdk.annotations.Experimental;
import org.apache.beam.sdk.annotations.Internal;
import org.apache.beam.sdk.extensions.sql.BeamSql;
import org.apache.beam.sdk.extensions.sql.BeamSqlCli;
import org.apache.beam.sdk.extensions.sql.BeamSqlTable;
import org.apache.beam.sdk.extensions.sql.BeamSqlUdf;
import org.apache.beam.sdk.extensions.sql.impl.interpreter.operator.UdafImpl;
import org.apache.beam.sdk.extensions.sql.meta.provider.ReadOnlyTableProvider;
import org.apache.beam.sdk.extensions.sql.meta.provider.TableProvider;
import org.apache.beam.sdk.extensions.sql.meta.store.InMemoryMetaStore;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionTuple;
import org.apache.beam.sdk.values.Row;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlExecutableStatement;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;

/**
 * {@link BeamSqlEnv} prepares the execution context for {@link BeamSql} and {@link BeamSqlCli}.
 *
 * <p>It contains a {@link SchemaPlus} which holds the metadata of tables/UDF functions, and a
 * {@link BeamQueryPlanner} which parse/validate/optimize/translate input SQL queries.
 */
@Internal
@Experimental
public class BeamSqlEnv {
  final CalciteConnection connection;
  final SchemaPlus defaultSchema;
  final BeamQueryPlanner planner;

  private BeamSqlEnv(TableProvider tableProvider) {
    connection = JdbcDriver.connect(tableProvider);
    defaultSchema = JdbcDriver.getDefaultSchema(connection);
    planner = new BeamQueryPlanner(connection);
  }

  public static BeamSqlEnv readOnly(String tableType, Map<String, BeamSqlTable> tables) {
    return withTableProvider(new ReadOnlyTableProvider(tableType, tables));
  }

  public static BeamSqlEnv withTableProvider(TableProvider tableProvider) {
    return new BeamSqlEnv(tableProvider);
  }

  public static BeamSqlEnv inMemory(TableProvider... tableProviders) {
    InMemoryMetaStore inMemoryMetaStore = new InMemoryMetaStore();
    for (TableProvider tableProvider : tableProviders) {
      inMemoryMetaStore.registerProvider(tableProvider);
    }

    return new BeamSqlEnv(inMemoryMetaStore);
  }

  /** Register a UDF function which can be used in SQL expression. */
  public void registerUdf(String functionName, Class<?> clazz, String method) {
    defaultSchema.add(functionName, ScalarFunctionImpl.create(clazz, method));
  }

  /** Register a UDF function which can be used in SQL expression. */
  public void registerUdf(String functionName, Class<? extends BeamSqlUdf> clazz) {
    registerUdf(functionName, clazz, BeamSqlUdf.UDF_METHOD);
  }

  /**
   * Register {@link SerializableFunction} as a UDF function which can be used in SQL expression.
   * Note, {@link SerializableFunction} must have a constructor without arguments.
   */
  public void registerUdf(String functionName, SerializableFunction sfn) {
    registerUdf(functionName, sfn.getClass(), "apply");
  }

  /**
   * Register a UDAF function which can be used in GROUP-BY expression. See {@link
   * org.apache.beam.sdk.transforms.Combine.CombineFn} on how to implement a UDAF.
   */
  public void registerUdaf(String functionName, Combine.CombineFn combineFn) {
    defaultSchema.add(functionName, new UdafImpl(combineFn));
  }

  public PTransform<PCollectionTuple, PCollection<Row>> parseQuery(String query)
      throws SqlParseException, RelConversionException, ValidationException {

    return planner.convertToBeamRel(query).toPTransform();
  }

  public boolean isDdl(String sqlStatement) throws SqlParseException {
    return planner.parse(sqlStatement) instanceof SqlExecutableStatement;
  }

  public void executeDdl(String sqlStatement) throws SqlParseException {
    SqlExecutableStatement ddl = (SqlExecutableStatement) planner.parse(sqlStatement);
    ddl.execute(getContext());
  }

  public CalcitePrepare.Context getContext() {
    return connection.createPrepareContext();
  }

  public String explain(String sqlString)
      throws SqlParseException, RelConversionException, ValidationException {
    return RelOptUtil.toString(planner.convertToBeamRel(sqlString));
  }
}
