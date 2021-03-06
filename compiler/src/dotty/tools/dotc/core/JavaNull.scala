package dotty.tools.dotc.core

import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Flags.JavaDefined
import dotty.tools.dotc.core.StdNames.{jnme, nme}
import dotty.tools.dotc.core.Symbols.{Symbol, defn, _}
import dotty.tools.dotc.core.Types.{AndType, AppliedType, LambdaType, MethodType, OrType, PolyType, Type, TypeAlias, TypeMap, TypeParamRef, TypeRef}

/** Transformation from Java (nullable) to Scala (non-nullable) types */
object JavaNull {

  /** Adds nullability annotations to a Java-defined member.
   *  `tp` is the member type. The type inside `sym` shouldn't be used (might not be even set).
   */
  def nullifyMember(sym: Symbol, tp: Type)(implicit ctx: Context): Type = {
    assert(sym.is(JavaDefined), s"can only nullify java-defined members")

    // A list of members that are special-cased.
    val whitelist: Seq[NullifyPolicy] = Seq(
      // The `TYPE` field in every class.
      FieldP(_.name == nme.TYPE_),
      // The `toString` method.
      MethodP(_.name == nme.toString_),
      // Constructors: params are nullified, but the result type isn't.
      paramsOnlyP(_.isConstructor)
    ) ++ Seq(
      // Methods in `java.lang.String`.
      paramsOnlyP(_.name == nme.concat),
      paramsOnlyP(_.name == nme.replace),
      paramsOnlyP(_.name == nme.replaceFirst),
      paramsOnlyP(_.name == nme.replaceAll),
      paramsOnlyP(_.name == nme.split),
      paramsOnlyP(_.name == nme.toLowerCase),
      paramsOnlyP(_.name == nme.toUpperCase),
      paramsOnlyP(_.name == nme.trim),
      paramsOnlyP(_.name == nme.toCharArray),
      paramsOnlyP(_.name == nme.substring)
    ).map(WithinSym(_, defn.StringClass)) ++ Seq(
      // Methods in `java.lang.Class`
      paramsOnlyP(_.name == nme.newInstance),
      paramsOnlyP(_.name == nme.asSubclass),
      paramsOnlyP(_.name == jnme.ForName),
    ).map(WithinSym(_, defn.ClassClass))


    val (fromWhitelistTp, handled) = whitelist.foldLeft((tp, false)) {
      case (res@(_, true), _) => res
      case ((_, false), pol) =>
        if (pol.isApplicable(sym)) (pol(tp), true)
        else (tp, false)
    }

    if (handled) {
      fromWhitelistTp
    } else {
      // Default case: nullify everything.
      nullifyType(tp)
    }
  }

  /** A policy that special cases the handling of some symbol or class of symbols. */
  private sealed trait NullifyPolicy {
    /** Whether the policy applies to `sym`. */
    def isApplicable(sym: Symbol): Boolean
    /** Nullifies `tp` according to the policy. Should call `isApplicable` first. */
    def apply(tp: Type): Type
  }

  /** A policy that avoids modifying a field. */
  private case class FieldP(trigger: Symbol => Boolean)(implicit ctx: Context) extends NullifyPolicy {
    override def isApplicable(sym: Symbol): Boolean = trigger(sym)
    override def apply(tp: Type): Type = {
      assert(!tp.isJavaMethod, s"FieldPolicy applies to method (non-field) type ${tp.show}")
      tp
    }
  }

  /** A policy for handling a method or poly. Can indicate whether the argument or return types should be nullified. */
  private case class MethodP(trigger: Symbol => Boolean,
                     nlfyParams: Boolean = false,
                     nlfyRes: Boolean = false)
                    (implicit ctx: Context) extends TypeMap with NullifyPolicy {
    override def isApplicable(sym: Symbol): Boolean = trigger(sym)

    override def apply(tp: Type): Type = {
      tp match {
        case ptp: PolyType =>
          derivedLambdaType(ptp)(ptp.paramInfos, this(ptp.resType))
        case mtp: MethodType =>
          val paramTpes = if (nlfyParams) mtp.paramInfos.mapConserve(nullifyType) else mtp.paramInfos
          val resTpe = if (nlfyRes) nullifyType(mtp.resType) else mtp.resType
          derivedLambdaType(mtp)(paramTpes, resTpe)
      }
    }
  }

  /** A policy that nullifies only method parameters (but not result types). */
  private def paramsOnlyP(trigger: Symbol => Boolean)(implicit ctx: Context): MethodP = {
    MethodP(trigger, nlfyParams = true, nlfyRes = false)
  }

  /** A wrapper policy that works as `inner` but additionally verifies that the symbol is contained in `owner`. */
  private case class WithinSym(inner: NullifyPolicy, owner: Symbol)(implicit ctx: Context) extends NullifyPolicy {
    override def isApplicable(sym: Symbol): Boolean = sym.owner == owner && inner.isApplicable(sym)

    override def apply(tp: Type): Type = inner(tp)
  }

  /** Nullifies a Java type by adding `| JavaNull` in the relevant places.
   *  We need this because Java types remain implicitly nullable.
   */
  private def nullifyType(tpe: Type)(implicit ctx: Context): Type = {
    val nullMap = new JavaNullMap(alreadyNullable = false)
    nullMap(tpe)
  }

  /** A type map that adds `| JavaNull`.
   *  @param alreadyNullable whether the type being mapped is already nullable (at the outermost level)
   */
  class JavaNullMap(alreadyNullable: Boolean)(implicit ctx: Context) extends TypeMap {
    /** Should we nullify `tp` at the outermost level? */
    def shouldNullify(tp: Type): Boolean = {
      !alreadyNullable && (tp match {
        case tp: TypeRef => !tp.symbol.isValueClass && !tp.isRef(defn.AnyClass) && !tp.isRef(defn.RefEqClass)
        case _ => true
      })
    }

    /** Should we nullify the arguments to the given generic `tp`?
     *  We only nullify the inside of Scala-defined constructors.
     *  This is because Java classes are _all_ nullified, so both `java.util.List[String]` and
     *  `java.util.List[String|Null]` contain nullable elements.
     */
    def shouldDescend(tp: AppliedType): Boolean = {
      val AppliedType(tycons, _) = tp
      tycons.widenDealias match {
        case tp: TypeRef if !tp.symbol.is(JavaDefined) => true
        case _ => false
      }
    }

    override def apply(tp: Type): Type = {
      tp match {
        case tp: LambdaType => mapOver(tp)
        case tp: TypeAlias => mapOver(tp)
        case tp@AndType(tp1, tp2) =>
          // nullify(A & B) = (nullify(A) & nullify(B)) | Null, but take care not to add
          // duplicate `Null`s at the outermost level inside `A` and `B`.
          val newMap = new JavaNullMap(alreadyNullable = true)
          derivedAndType(tp, newMap(tp1), newMap(tp2)).toJavaNullable
        case tp@OrType(tp1, tp2) if !tp.isJavaNullableUnion =>
          val newMap = new JavaNullMap(alreadyNullable = true)
          derivedOrType(tp, newMap(tp1), newMap(tp2)).toJavaNullable
        case tp: TypeRef if shouldNullify(tp) => tp.toJavaNullable
        case tp: TypeParamRef if shouldNullify(tp) => tp.toJavaNullable
        case appTp@AppliedType(tycons, targs) =>
          val targs2 = if (shouldDescend(appTp)) targs map this else targs
          derivedAppliedType(appTp, tycons, targs2).toJavaNullable
        case _ => tp
      }
    }
  }
}
