/**
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package eu.stratosphere.scala.operators

import language.experimental.macros
import scala.reflect.macros.Context

import eu.stratosphere.pact.common.contract.MapContract

import eu.stratosphere.pact.common.`type`.PactRecord
import eu.stratosphere.pact.common.stubs.Collector
import eu.stratosphere.pact.generic.contract.Contract
import eu.stratosphere.pact.common.contract.CrossContract

import eu.stratosphere.nephele.configuration.Configuration
import java.util.{ Iterator => JIterator }


import eu.stratosphere.scala.contracts.Annotations
import eu.stratosphere.scala._
import eu.stratosphere.scala.analysis._
import eu.stratosphere.scala.stubs.{CrossStubBase, CrossStub, FlatCrossStub}
import eu.stratosphere.scala.codegen.{MacroContextHolder, Util}
import eu.stratosphere.scala.stubs.DeserializingIterator

class CrossDataSet[LeftIn, RightIn](val leftInput: DataSet[LeftIn], val rightInput: DataSet[RightIn]) {
  def map[Out](fun: (LeftIn, RightIn) => Out): DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out] = macro CrossMacros.map[LeftIn, RightIn, Out]
  def flatMap[Out](fun: (LeftIn, RightIn) => Iterator[Out]): DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out] = macro CrossMacros.flatMap[LeftIn, RightIn, Out]
  def filter(fun: (LeftIn, RightIn) => Boolean): DataSet[(LeftIn, RightIn)] with TwoInputHintable[LeftIn, RightIn, (LeftIn, RightIn)] = macro CrossMacros.filter[LeftIn, RightIn]
}

object CrossMacros {

  def map[LeftIn: c.WeakTypeTag, RightIn: c.WeakTypeTag, Out: c.WeakTypeTag](c: Context { type PrefixType = CrossDataSet[LeftIn, RightIn] })
                                                                            (fun: c.Expr[(LeftIn, RightIn) => Out]): c.Expr[DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out]] = {
    import c.universe._

    val slave = MacroContextHolder.newMacroHelper(c)
    
    val (udtLeftIn, createUdtLeftIn) = slave.mkUdtClass[LeftIn]
    val (udtRightIn, createUdtRightIn) = slave.mkUdtClass[RightIn]
    val (udtOut, createUdtOut) = slave.mkUdtClass[Out]

    val stub: c.Expr[CrossStubBase[LeftIn, RightIn, Out]] = if (fun.actualType <:< weakTypeOf[CrossStub[LeftIn, RightIn, Out]])
      reify { fun.splice.asInstanceOf[CrossStubBase[LeftIn, RightIn, Out]] }
    else reify {
      implicit val leftInputUDT: UDT[LeftIn] = c.Expr[UDT[LeftIn]](createUdtLeftIn).splice
      implicit val rightInputUDT: UDT[RightIn] = c.Expr[UDT[RightIn]](createUdtRightIn).splice
      implicit val outputUDT: UDT[Out] = c.Expr[UDT[Out]](createUdtOut).splice
      new CrossStubBase[LeftIn, RightIn, Out] {
        override def cross(leftRecord: PactRecord, rightRecord: PactRecord, out: Collector[PactRecord]) = {
          val left = leftDeserializer.deserializeRecyclingOn(leftRecord)
          val right = rightDeserializer.deserializeRecyclingOn(rightRecord)
          val output = fun.splice.apply(left, right)

          leftRecord.setNumFields(outputLength)

          for (field <- leftDiscard)
            leftRecord.setNull(field)

          leftRecord.copyFrom(rightRecord, rightForwardFrom, rightForwardTo)
          leftRecord.copyFrom(leftRecord, leftForwardFrom, leftForwardTo)

          serializer.serialize(output, leftRecord)
          out.collect(leftRecord)
        }
      }
    }
    val contract = reify {
      val helper: CrossDataSet[LeftIn, RightIn] = c.prefix.splice
      val leftInput = helper.leftInput.contract
      val rightInput = helper.rightInput.contract
      val generatedStub = ClosureCleaner.clean(stub.splice)
      val builder = CrossContract.builder(generatedStub).input1(leftInput).input2(rightInput)
      
      val ret = new CrossContract(builder) with TwoInputScalaContract[LeftIn, RightIn, Out] {
        override def getUDF = generatedStub.udf
        override def annotations = Seq(
          Annotations.getConstantFieldsFirst(
            Util.filterNonForwards(getUDF.getLeftForwardIndexArrayFrom, getUDF.getLeftForwardIndexArrayTo)),
          Annotations.getConstantFieldsSecond(
            Util.filterNonForwards(getUDF.getRightForwardIndexArrayFrom, getUDF.getRightForwardIndexArrayTo)))
      }
      new DataSet[Out](ret) with TwoInputHintable[LeftIn, RightIn, Out] {}
    }

    val result = c.Expr[DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out]](Block(List(udtLeftIn, udtRightIn, udtOut), contract.tree))
    
    return result
  }
  
  def flatMap[LeftIn: c.WeakTypeTag, RightIn: c.WeakTypeTag, Out: c.WeakTypeTag](c: Context { type PrefixType = CrossDataSet[LeftIn, RightIn] })(fun: c.Expr[(LeftIn, RightIn) => Iterator[Out]]): c.Expr[DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out]] = {
    import c.universe._

    val slave = MacroContextHolder.newMacroHelper(c)
    
    val (udtLeftIn, createUdtLeftIn) = slave.mkUdtClass[LeftIn]
    val (udtRightIn, createUdtRightIn) = slave.mkUdtClass[RightIn]
    val (udtOut, createUdtOut) = slave.mkUdtClass[Out]

    val stub: c.Expr[CrossStubBase[LeftIn, RightIn, Out]] = if (fun.actualType <:< weakTypeOf[CrossStub[LeftIn, RightIn, Out]])
      reify { fun.splice.asInstanceOf[CrossStubBase[LeftIn, RightIn, Out]] }
    else reify {
      implicit val leftInputUDT: UDT[LeftIn] = c.Expr[UDT[LeftIn]](createUdtLeftIn).splice
      implicit val rightInputUDT: UDT[RightIn] = c.Expr[UDT[RightIn]](createUdtRightIn).splice
      implicit val outputUDT: UDT[Out] = c.Expr[UDT[Out]](createUdtOut).splice
      new CrossStubBase[LeftIn, RightIn, Out] {
        override def cross(leftRecord: PactRecord, rightRecord: PactRecord, out: Collector[PactRecord]) = {
          val left = leftDeserializer.deserializeRecyclingOn(leftRecord)
          val right = rightDeserializer.deserializeRecyclingOn(rightRecord)
          val output = fun.splice.apply(left, right)

          if (output.nonEmpty) {

            leftRecord.setNumFields(outputLength)

            for (field <- leftDiscard)
              leftRecord.setNull(field)

            leftRecord.copyFrom(rightRecord, rightForwardFrom, rightForwardTo)
            leftRecord.copyFrom(leftRecord, leftForwardFrom, leftForwardTo)

            for (item <- output) {
              serializer.serialize(item, leftRecord)
              out.collect(leftRecord)
            }
          }
        }
      }
    }
    val contract = reify {
      val helper: CrossDataSet[LeftIn, RightIn] = c.prefix.splice
      val leftInput = helper.leftInput.contract
      val rightInput = helper.rightInput.contract
      val generatedStub = ClosureCleaner.clean(stub.splice)
      val builder = CrossContract.builder(generatedStub).input1(leftInput).input2(rightInput)
      
      val ret = new CrossContract(builder) with TwoInputScalaContract[LeftIn, RightIn, Out] {
        override def getUDF = generatedStub.udf
        override def annotations = Seq(
          Annotations.getConstantFieldsFirst(
            Util.filterNonForwards(getUDF.getLeftForwardIndexArrayFrom, getUDF.getLeftForwardIndexArrayTo)),
          Annotations.getConstantFieldsSecond(
            Util.filterNonForwards(getUDF.getRightForwardIndexArrayFrom, getUDF.getRightForwardIndexArrayTo)))
      }
      new DataSet[Out](ret) with TwoInputHintable[LeftIn, RightIn, Out] {}
    }

    val result = c.Expr[DataSet[Out] with TwoInputHintable[LeftIn, RightIn, Out]](Block(List(udtLeftIn, udtRightIn, udtOut), contract.tree))
    
    return result
  }

  def filter[LeftIn: c.WeakTypeTag, RightIn: c.WeakTypeTag](c: Context { type PrefixType = CrossDataSet[LeftIn, RightIn] })(fun: c.Expr[(LeftIn, RightIn) => Boolean]): c.Expr[DataSet[(LeftIn, RightIn)] with TwoInputHintable[LeftIn, RightIn, (LeftIn, RightIn)]] = {
    import c.universe._

    val slave = MacroContextHolder.newMacroHelper(c)

    val (udtLeftIn, createUdtLeftIn) = slave.mkUdtClass[LeftIn]
    val (udtRightIn, createUdtRightIn) = slave.mkUdtClass[RightIn]
    val (udtOut, createUdtOut) = slave.mkUdtClass[(LeftIn, RightIn)]

    val stub = reify {
      implicit val leftInputUDT: UDT[LeftIn] = c.Expr[UDT[LeftIn]](createUdtLeftIn).splice
      implicit val rightInputUDT: UDT[RightIn] = c.Expr[UDT[RightIn]](createUdtRightIn).splice
      implicit val outputUDT: UDT[(LeftIn, RightIn)] = c.Expr[UDT[(LeftIn, RightIn)]](createUdtOut).splice
      new CrossStubBase[LeftIn, RightIn, (LeftIn, RightIn)] {
        override def cross(leftRecord: PactRecord, rightRecord: PactRecord, out: Collector[PactRecord]) = {
          val left = leftDeserializer.deserializeRecyclingOn(leftRecord)
          val right = rightDeserializer.deserializeRecyclingOn(rightRecord)
          if (fun.splice.apply(left, right)) {
            val output = (left, right)
            leftRecord.setNumFields(outputLength)
            serializer.serialize(output, leftRecord)
            out.collect(leftRecord)
          }
        }
      }
    }
    val contract = reify {
      val helper: CrossDataSet[LeftIn, RightIn] = c.prefix.splice
      val leftInput = helper.leftInput.contract
      val rightInput = helper.rightInput.contract
      val generatedStub = ClosureCleaner.clean(stub.splice)
      val builder = CrossContract.builder(generatedStub).input1(leftInput).input2(rightInput)
      
      val ret = new CrossContract(builder) with TwoInputScalaContract[LeftIn, RightIn, (LeftIn, RightIn)] {
        override def getUDF = generatedStub.udf
        override def annotations = Seq(
          Annotations.getConstantFieldsFirst(
            Util.filterNonForwards(getUDF.getLeftForwardIndexArrayFrom, getUDF.getLeftForwardIndexArrayTo)),
          Annotations.getConstantFieldsSecond(
            Util.filterNonForwards(getUDF.getRightForwardIndexArrayFrom, getUDF.getRightForwardIndexArrayTo)))

      }
      new DataSet[(LeftIn, RightIn)](ret) with TwoInputHintable[LeftIn, RightIn, (LeftIn, RightIn)] {}
    }

    val result = c.Expr[DataSet[(LeftIn, RightIn)] with TwoInputHintable[LeftIn, RightIn, (LeftIn, RightIn)]](Block(List(udtLeftIn, udtRightIn, udtOut), contract.tree))
    
    return result
  }
}
