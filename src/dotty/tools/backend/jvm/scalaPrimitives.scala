/* NSC -- new Scala compiler
 * Copyright 2005-2012 LAMP/EPFL
 * @author  Martin Odersky
 */

package dotty.tools.dotc
package backend.jvm

import dotty.tools.backend.jvm.GenBCodePipeline
import dotty.tools.dotc.ast.Trees.Select
import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.core.StdNames
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Types.{JavaArrayType, ErrorType, Type}

import scala.collection.{ mutable, immutable }

import core.Contexts.Context
import core.Symbols.{Symbol, NoSymbol}

/** Scala primitive operations are represented as methods in `Any` and
 *  `AnyVal` subclasses. Here we demultiplex them by providing a mapping
 *  from their symbols to integers. Different methods exist for
 *  different value types, but with the same meaning (like plus, minus,
 *  etc.). They will all be mapped to the same int.
 *
 *  Note: The three equal methods have the following semantics:
 *  - `"=="` checks for `null`, and if non-null, calls
 *    `java.lang.Object.equals`
 *    `(class: Any; modifier: final)`. Primitive: `EQ`
 *  - `"eq"` usual reference comparison
 *    `(class: AnyRef; modifier: final)`. Primitive: `ID`
 *  - `"equals"` user-defined equality (Java semantics)
 *    `(class: Object; modifier: none)`. Primitive: `EQUALS`
 *
 * Inspired from the `scalac` compiler.
 */
class DottyPrimitives(ctx: Context) {
  import scala.tools.nsc.backend.ScalaPrimitives._

  private lazy val primitives: immutable.Map[Symbol, Int] = init

  /** Return the code for the given symbol. */
  def getPrimitive(sym: Symbol): Int = {
    primitives(sym)
  }

  /**
   * Return the primitive code of the given operation. If the
   * operation is an array get/set, we inspect the type of the receiver
   * to demux the operation.
   *
   * @param fun The method symbol
   * @param tpe The type of the receiver object. It is used only for array
   *            operations
   */
  def getPrimitive(app: Apply, tpe: Type)(implicit ctx: Context): Int = {
    val fun = app.fun.symbol
    val defn = ctx.definitions
    val code = app.fun match {
      case Select(_, nme.primitive.arrayLength) =>
        LENGTH
      case Select(_, nme.primitive.arrayUpdate) =>
        UPDATE
      case Select(_, nme.primitive.arrayApply) =>
        APPLY
      case _ => getPrimitive(fun)
    }

    def elementType: Type = tpe.widenDealias match {
      case defn.ArrayType(el) => el
      case JavaArrayType(el) => el
      case _ =>
        ctx.error(s"expected Array $tpe")
        ErrorType
    }

    code match {

      case APPLY =>
        elementType.classSymbol match {
          case defn.BooleanClass    => ZARRAY_GET
          case defn.ByteClass       => BARRAY_GET
          case defn.ShortClass      => SARRAY_GET
          case defn.CharClass       => CARRAY_GET
          case defn.IntClass        => IARRAY_GET
          case defn.LongClass       => LARRAY_GET
          case defn.FloatClass      => FARRAY_GET
          case defn.DoubleClass     => DARRAY_GET
          case _                    => OARRAY_GET
        }

      case UPDATE =>
        elementType.classSymbol match {
          case defn.BooleanClass    => ZARRAY_SET
          case defn.ByteClass       => BARRAY_SET
          case defn.ShortClass      => SARRAY_SET
          case defn.CharClass       => CARRAY_SET
          case defn.IntClass        => IARRAY_SET
          case defn.LongClass       => LARRAY_SET
          case defn.FloatClass      => FARRAY_SET
          case defn.DoubleClass     => DARRAY_SET
          case _                    => OARRAY_SET
        }

      case LENGTH =>
        elementType.classSymbol match {
          case defn.BooleanClass    => ZARRAY_LENGTH
          case defn.ByteClass       => BARRAY_LENGTH
          case defn.ShortClass      => SARRAY_LENGTH
          case defn.CharClass       => CARRAY_LENGTH
          case defn.IntClass        => IARRAY_LENGTH
          case defn.LongClass       => LARRAY_LENGTH
          case defn.FloatClass      => FARRAY_LENGTH
          case defn.DoubleClass     => DARRAY_LENGTH
          case _                    => OARRAY_LENGTH
        }

      case _ =>
        code
    }
  }

  /** Initialize the primitive map */
  private def init: immutable.Map[Symbol, Int]  = {

    implicit val ctx: Context = this.ctx

    import core.Symbols.defn
    val primitives = new mutable.HashMap[Symbol, Int]()

    /** Add a primitive operation to the map */
    def addPrimitive(s: Symbol, code: Int): Unit = {
      assert(!(primitives contains s), "Duplicate primitive " + s)
      primitives(s) = code
    }

    def addPrimitives(cls: Symbol, method: TermName, code: Int)(implicit ctx: Context): Unit = {
      val alts = cls.info.member(method).alternatives.map(_.symbol)
      if (alts.isEmpty)
        ctx.error(s"Unknown primitive method $cls.$method")
      else alts foreach (s =>
        addPrimitive(s,
          s.info.paramTypess match {
            case List(tp :: _) if code == ADD && tp =:= ctx.definitions.StringType => CONCAT
            case _                                          => code
          }
        )
        )
    }

    // scala.Any
    addPrimitive(defn.Any_==, EQ)
    addPrimitive(defn.Any_!=, NE)
    addPrimitive(defn.Any_isInstanceOf, IS)
    addPrimitive(defn.Any_asInstanceOf, AS)
    addPrimitive(defn.Any_##, HASH)

    // java.lang.Object
    addPrimitive(defn.Object_eq, ID)
    addPrimitive(defn.Object_ne, NI)
 /*   addPrimitive(defn.Any_==, EQ)
    addPrimitive(defn.Any_!=, NE)*/
    addPrimitive(defn.Object_synchronized, SYNCHRONIZED)
    /*addPrimitive(defn.Any_isInstanceOf, IS)
    addPrimitive(defn.Any_asInstanceOf, AS)*/

    // java.lang.String
    addPrimitive(defn.String_+, CONCAT)

    import core.StdNames.nme

    // scala.Array
    lazy val ArrayClass = defn.ArrayClass
    addPrimitives(ArrayClass, nme.length, LENGTH)
    addPrimitives(ArrayClass, nme.apply, APPLY)
    addPrimitives(ArrayClass, nme.update, UPDATE)

    // scala.Boolean
    lazy val BooleanClass = defn.BooleanClass
    addPrimitives(BooleanClass, nme.EQ, EQ)
    addPrimitives(BooleanClass, nme.NE, NE)
    addPrimitives(BooleanClass, nme.UNARY_!, ZNOT)
    addPrimitives(BooleanClass, nme.ZOR, ZOR)
    addPrimitives(BooleanClass, nme.ZAND, ZAND)
    addPrimitives(BooleanClass, nme.OR, OR)
    addPrimitives(BooleanClass, nme.AND, AND)
    addPrimitives(BooleanClass, nme.XOR, XOR)

    // scala.Byte
    lazy val ByteClass = defn.ByteClass
    addPrimitives(ByteClass, nme.EQ, EQ)
    addPrimitives(ByteClass, nme.NE, NE)
    addPrimitives(ByteClass, nme.ADD, ADD)
    addPrimitives(ByteClass, nme.SUB, SUB)
    addPrimitives(ByteClass, nme.MUL, MUL)
    addPrimitives(ByteClass, nme.DIV, DIV)
    addPrimitives(ByteClass, nme.MOD, MOD)
    addPrimitives(ByteClass, nme.LT, LT)
    addPrimitives(ByteClass, nme.LE, LE)
    addPrimitives(ByteClass, nme.GT, GT)
    addPrimitives(ByteClass, nme.GE, GE)
    addPrimitives(ByteClass, nme.XOR, XOR)
    addPrimitives(ByteClass, nme.OR, OR)
    addPrimitives(ByteClass, nme.AND, AND)
    addPrimitives(ByteClass, nme.LSL, LSL)
    addPrimitives(ByteClass, nme.LSR, LSR)
    addPrimitives(ByteClass, nme.ASR, ASR)
      // conversions
    addPrimitives(ByteClass, nme.toByte,   B2B)
    addPrimitives(ByteClass, nme.toShort,  B2S)
    addPrimitives(ByteClass, nme.toChar,   B2C)
    addPrimitives(ByteClass, nme.toInt,    B2I)
    addPrimitives(ByteClass, nme.toLong,   B2L)
    // unary methods
    addPrimitives(ByteClass, nme.UNARY_+, POS)
    addPrimitives(ByteClass, nme.UNARY_-, NEG)
    addPrimitives(ByteClass, nme.UNARY_~, NOT)

    addPrimitives(ByteClass, nme.toFloat,  B2F)
    addPrimitives(ByteClass, nme.toDouble, B2D)

    // scala.Short
    lazy val ShortClass = defn.ShortClass
    addPrimitives(ShortClass, nme.EQ, EQ)
    addPrimitives(ShortClass, nme.NE, NE)
    addPrimitives(ShortClass, nme.ADD, ADD)
    addPrimitives(ShortClass, nme.SUB, SUB)
    addPrimitives(ShortClass, nme.MUL, MUL)
    addPrimitives(ShortClass, nme.DIV, DIV)
    addPrimitives(ShortClass, nme.MOD, MOD)
    addPrimitives(ShortClass, nme.LT, LT)
    addPrimitives(ShortClass, nme.LE, LE)
    addPrimitives(ShortClass, nme.GT, GT)
    addPrimitives(ShortClass, nme.GE, GE)
    addPrimitives(ShortClass, nme.XOR, XOR)
    addPrimitives(ShortClass, nme.OR, OR)
    addPrimitives(ShortClass, nme.AND, AND)
    addPrimitives(ShortClass, nme.LSL, LSL)
    addPrimitives(ShortClass, nme.LSR, LSR)
    addPrimitives(ShortClass, nme.ASR, ASR)
      // conversions
    addPrimitives(ShortClass, nme.toByte,   S2B)
    addPrimitives(ShortClass, nme.toShort,  S2S)
    addPrimitives(ShortClass, nme.toChar,   S2C)
    addPrimitives(ShortClass, nme.toInt,    S2I)
    addPrimitives(ShortClass, nme.toLong,   S2L)
    // unary methods
    addPrimitives(ShortClass, nme.UNARY_+, POS)
    addPrimitives(ShortClass, nme.UNARY_-, NEG)
    addPrimitives(ShortClass, nme.UNARY_~, NOT)

    addPrimitives(ShortClass, nme.toFloat,  S2F)
    addPrimitives(ShortClass, nme.toDouble, S2D)

    // scala.Char
    lazy val CharClass = defn.CharClass
    addPrimitives(CharClass, nme.EQ, EQ)
    addPrimitives(CharClass, nme.NE, NE)
    addPrimitives(CharClass, nme.ADD, ADD)
    addPrimitives(CharClass, nme.SUB, SUB)
    addPrimitives(CharClass, nme.MUL, MUL)
    addPrimitives(CharClass, nme.DIV, DIV)
    addPrimitives(CharClass, nme.MOD, MOD)
    addPrimitives(CharClass, nme.LT, LT)
    addPrimitives(CharClass, nme.LE, LE)
    addPrimitives(CharClass, nme.GT, GT)
    addPrimitives(CharClass, nme.GE, GE)
    addPrimitives(CharClass, nme.XOR, XOR)
    addPrimitives(CharClass, nme.OR, OR)
    addPrimitives(CharClass, nme.AND, AND)
    addPrimitives(CharClass, nme.LSL, LSL)
    addPrimitives(CharClass, nme.LSR, LSR)
    addPrimitives(CharClass, nme.ASR, ASR)
      // conversions
    addPrimitives(CharClass, nme.toByte,   C2B)
    addPrimitives(CharClass, nme.toShort,  C2S)
    addPrimitives(CharClass, nme.toChar,   C2C)
    addPrimitives(CharClass, nme.toInt,    C2I)
    addPrimitives(CharClass, nme.toLong,   C2L)
    // unary methods
    addPrimitives(CharClass, nme.UNARY_+, POS)
    addPrimitives(CharClass, nme.UNARY_-, NEG)
    addPrimitives(CharClass, nme.UNARY_~, NOT)
    addPrimitives(CharClass, nme.toFloat,  C2F)
    addPrimitives(CharClass, nme.toDouble, C2D)

    // scala.Int
    lazy val IntClass = defn.IntClass
    addPrimitives(IntClass, nme.EQ, EQ)
    addPrimitives(IntClass, nme.NE, NE)
    addPrimitives(IntClass, nme.ADD, ADD)
    addPrimitives(IntClass, nme.SUB, SUB)
    addPrimitives(IntClass, nme.MUL, MUL)
    addPrimitives(IntClass, nme.DIV, DIV)
    addPrimitives(IntClass, nme.MOD, MOD)
    addPrimitives(IntClass, nme.LT, LT)
    addPrimitives(IntClass, nme.LE, LE)
    addPrimitives(IntClass, nme.GT, GT)
    addPrimitives(IntClass, nme.GE, GE)
    addPrimitives(IntClass, nme.XOR, XOR)
    addPrimitives(IntClass, nme.OR, OR)
    addPrimitives(IntClass, nme.AND, AND)
    addPrimitives(IntClass, nme.LSL, LSL)
    addPrimitives(IntClass, nme.LSR, LSR)
    addPrimitives(IntClass, nme.ASR, ASR)
      // conversions
    addPrimitives(IntClass, nme.toByte,   I2B)
    addPrimitives(IntClass, nme.toShort,  I2S)
    addPrimitives(IntClass, nme.toChar,   I2C)
    addPrimitives(IntClass, nme.toInt,    I2I)
    addPrimitives(IntClass, nme.toLong,   I2L)
    // unary methods
    addPrimitives(IntClass, nme.UNARY_+, POS)
    addPrimitives(IntClass, nme.UNARY_-, NEG)
    addPrimitives(IntClass, nme.UNARY_~, NOT)
    addPrimitives(IntClass, nme.toFloat,  I2F)
    addPrimitives(IntClass, nme.toDouble, I2D)

    // scala.Long
    lazy val LongClass = defn.LongClass
    addPrimitives(LongClass, nme.EQ, EQ)
    addPrimitives(LongClass, nme.NE, NE)
    addPrimitives(LongClass, nme.ADD, ADD)
    addPrimitives(LongClass, nme.SUB, SUB)
    addPrimitives(LongClass, nme.MUL, MUL)
    addPrimitives(LongClass, nme.DIV, DIV)
    addPrimitives(LongClass, nme.MOD, MOD)
    addPrimitives(LongClass, nme.LT, LT)
    addPrimitives(LongClass, nme.LE, LE)
    addPrimitives(LongClass, nme.GT, GT)
    addPrimitives(LongClass, nme.GE, GE)
    addPrimitives(LongClass, nme.XOR, XOR)
    addPrimitives(LongClass, nme.OR, OR)
    addPrimitives(LongClass, nme.AND, AND)
    addPrimitives(LongClass, nme.LSL, LSL)
    addPrimitives(LongClass, nme.LSR, LSR)
    addPrimitives(LongClass, nme.ASR, ASR)
      // conversions
    addPrimitives(LongClass, nme.toByte,   L2B)
    addPrimitives(LongClass, nme.toShort,  L2S)
    addPrimitives(LongClass, nme.toChar,   L2C)
    addPrimitives(LongClass, nme.toInt,    L2I)
    addPrimitives(LongClass, nme.toLong,   L2L)
    // unary methods
    addPrimitives(LongClass, nme.UNARY_+, POS)
    addPrimitives(LongClass, nme.UNARY_-, NEG)
    addPrimitives(LongClass, nme.UNARY_~, NOT)
    addPrimitives(LongClass, nme.toFloat,  L2F)
    addPrimitives(LongClass, nme.toDouble, L2D)

    // scala.Float
    lazy val FloatClass = defn.FloatClass
    addPrimitives(FloatClass, nme.EQ, EQ)
    addPrimitives(FloatClass, nme.NE, NE)
    addPrimitives(FloatClass, nme.ADD, ADD)
    addPrimitives(FloatClass, nme.SUB, SUB)
    addPrimitives(FloatClass, nme.MUL, MUL)
    addPrimitives(FloatClass, nme.DIV, DIV)
    addPrimitives(FloatClass, nme.MOD, MOD)
    addPrimitives(FloatClass, nme.LT, LT)
    addPrimitives(FloatClass, nme.LE, LE)
    addPrimitives(FloatClass, nme.GT, GT)
    addPrimitives(FloatClass, nme.GE, GE)
    // conversions
    addPrimitives(FloatClass, nme.toByte,   F2B)
    addPrimitives(FloatClass, nme.toShort,  F2S)
    addPrimitives(FloatClass, nme.toChar,   F2C)
    addPrimitives(FloatClass, nme.toInt,    F2I)
    addPrimitives(FloatClass, nme.toLong,   F2L)
    addPrimitives(FloatClass, nme.toFloat,  F2F)
    addPrimitives(FloatClass, nme.toDouble, F2D)
    // unary methods
    addPrimitives(FloatClass, nme.UNARY_+, POS)
    addPrimitives(FloatClass, nme.UNARY_-, NEG)

    // scala.Double
    lazy val DoubleClass = defn.DoubleClass
    addPrimitives(DoubleClass, nme.EQ, EQ)
    addPrimitives(DoubleClass, nme.NE, NE)
    addPrimitives(DoubleClass, nme.ADD, ADD)
    addPrimitives(DoubleClass, nme.SUB, SUB)
    addPrimitives(DoubleClass, nme.MUL, MUL)
    addPrimitives(DoubleClass, nme.DIV, DIV)
    addPrimitives(DoubleClass, nme.MOD, MOD)
    addPrimitives(DoubleClass, nme.LT, LT)
    addPrimitives(DoubleClass, nme.LE, LE)
    addPrimitives(DoubleClass, nme.GT, GT)
    addPrimitives(DoubleClass, nme.GE, GE)
    // conversions
    addPrimitives(DoubleClass, nme.toByte,   D2B)
    addPrimitives(DoubleClass, nme.toShort,  D2S)
    addPrimitives(DoubleClass, nme.toChar,   D2C)
    addPrimitives(DoubleClass, nme.toInt,    D2I)
    addPrimitives(DoubleClass, nme.toLong,   D2L)
    addPrimitives(DoubleClass, nme.toFloat,  D2F)
    addPrimitives(DoubleClass, nme.toDouble, D2D)
    // unary methods
    addPrimitives(DoubleClass, nme.UNARY_+, POS)
    addPrimitives(DoubleClass, nme.UNARY_-, NEG)


    primitives.toMap
  }

  def isPrimitive(fun: Tree): Boolean = {
    (primitives contains fun.symbol(ctx)) ||
      (fun.symbol(ctx) == NoSymbol // the only trees that do not have a symbol assigned are array.{update,select,length,clone}}
       && (fun match {
        case Select(_, StdNames.nme.clone_) => false // but array.clone is NOT a primitive op.
        case _ => true
      }))
  }

}
