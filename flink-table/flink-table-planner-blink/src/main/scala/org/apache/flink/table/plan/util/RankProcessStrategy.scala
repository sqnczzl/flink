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
package org.apache.flink.table.plan.util

import org.apache.flink.table.plan.`trait`.TraitUtil

import org.apache.calcite.rel.metadata.RelMetadataQuery
import org.apache.calcite.rel.{RelCollation, RelNode}
import org.apache.calcite.util.ImmutableBitSet

import scala.collection.JavaConversions._

/**
  * Base class of Strategy to choose different rank process function.
  */
sealed trait RankProcessStrategy

case object AppendFastStrategy extends RankProcessStrategy

case object RetractStrategy extends RankProcessStrategy

case class UpdateFastStrategy(primaryKeys: Array[Int]) extends RankProcessStrategy {
  override def toString: String = "UpdateFastStrategy" + primaryKeys.mkString("[", ",", "]")
}

case class UnaryUpdateStrategy(primaryKeys: Array[Int]) extends RankProcessStrategy {
  override def toString: String = "UnaryUpdateStrategy" + primaryKeys.mkString("[", ",", "]")
}

object RankProcessStrategy {

  /**
    * Gets [[RankProcessStrategy]] based on input, partitionKey and orderKey.
    */
  def analyzeRankProcessStrategy(
      input: RelNode,
      partitionKey: ImmutableBitSet,
      orderKey: RelCollation,
      mq: RelMetadataQuery): RankProcessStrategy = {

    val fieldCollations = orderKey.getFieldCollations
    val isUpdateStream = !UpdatingPlanChecker.isAppendOnly(input)

    if (isUpdateStream) {
      val inputIsAccRetract = TraitUtil.isAccRetract(input)
      val uniqueKeys = mq.getUniqueKeys(input)
      if (inputIsAccRetract || uniqueKeys == null || uniqueKeys.isEmpty
        // unique key should contains partition key
        || !uniqueKeys.exists(k => k.contains(partitionKey))) {
        // input is AccRetract or extract the unique keys failed,
        // and we fall back to using retract rank
        RetractStrategy
      } else {
        // TODO get `isMonotonic` value by RelModifiedMonotonicity handler
        val isMonotonic = false

        if (isMonotonic) {
          //FIXME choose a set of primary key
          UpdateFastStrategy(uniqueKeys.iterator().next().toArray)
        } else {
          if (fieldCollations.length == 1) {
            // single sort key in update stream scenario (no monotonic)
            // we can utilize unary rank function to speed up processing
            UnaryUpdateStrategy(uniqueKeys.iterator().next().toArray)
          } else {
            // no other choices, have to use retract rank
            RetractStrategy
          }
        }
      }
    } else {
      AppendFastStrategy
    }
  }
}
