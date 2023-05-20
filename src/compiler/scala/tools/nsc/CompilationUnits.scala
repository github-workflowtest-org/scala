/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc

import scala.annotation.nowarn
import scala.reflect.internal.util.{FreshNameCreator, NoSourceFile, SourceFile}
import scala.collection.mutable, mutable.ArrayDeque

trait CompilationUnits { global: Global =>

  /** An object representing a missing compilation unit.
   */
  object NoCompilationUnit extends CompilationUnit(NoSourceFile) {
    override val isJava = false
    override def exists = false
    override def toString() = "NoCompilationUnit"
  }

  /** Creates a `FreshNameCreator` that reports an error if it is used during the typer phase */
  final def warningFreshNameCreator: FreshNameCreator = new FreshNameCreator {
    override def newName(prefix: String): String = {
      if (global.phase == currentRun.typerPhase) {
        devWarningDumpStack("Typer phase should not use the compilation unit scoped fresh name creator", 32)
      }
      super.newName(prefix)
    }
  }

  /** One unit of compilation that has been submitted to the compiler.
    * It typically corresponds to a single file of source code.  It includes
    * error-reporting hooks.  */
  @nowarn("""cat=deprecation&origin=scala\.reflect\.macros\.Universe\.CompilationUnitContextApi""")
  class CompilationUnit(val source: SourceFile, freshNameCreator: FreshNameCreator) extends CompilationUnitContextApi { self =>
    def this(source: SourceFile) = this(source, new FreshNameCreator)
    /** the fresh name creator */
    implicit val fresh: FreshNameCreator = freshNameCreator
    def freshTermName(prefix: String = nme.FRESH_TERM_NAME_PREFIX) = global.freshTermName(prefix)
    def freshTypeName(prefix: String)                              = global.freshTypeName(prefix)

    def sourceAt(pos: Position): String =
      if (pos.start < pos.end) new String(source.content.slice(pos.start, pos.end)) else ""

    /** the content of the compilation unit in tree form */
    var body: Tree = EmptyTree

    /** The position of the first xml literal encountered while parsing this compilation unit.
     * NoPosition if there were none. Write-once.
     */
    private[this] var _firstXmlPos: Position = NoPosition

    /** Record that we encountered XML. Should only be called once. */
    protected[nsc] def encounteredXml(pos: Position) = _firstXmlPos = pos

    /** Does this unit contain XML? */
    def hasXml = _firstXmlPos ne NoPosition

    /** Position of first XML literal in this unit. */
    def firstXmlPos = _firstXmlPos

    def exists = source != NoSourceFile && source != null

    /** Note: depends now contains toplevel classes.
     *  To get their sourcefiles, you need to dereference with .sourcefile
     */
    private[this] val _depends = if (settings.YtrackDependencies.value) mutable.HashSet[Symbol]() else null
    @deprecated("Not supported and no longer used by Zinc", "2.12.9")
    def depends: mutable.HashSet[Symbol] = if (_depends == null) mutable.HashSet[Symbol]() else _depends
    def registerDependency(symbol: Symbol): Unit = if (settings.YtrackDependencies.value) {
      // sbt compatibility (scala/bug#6875)
      //
      // imagine we have a file named A.scala, which defines a trait named Foo and a module named Main
      // Main contains a call to a macro, which calls compileLate to define a mock for Foo
      // compileLate creates a virtual file Virt35af32.scala, which contains a class named FooMock extending Foo,
      // and macro expansion instantiates FooMock. the stage is now set. let's see what happens next.
      //
      // without this workaround in scalac or without being patched itself, sbt will think that
      // * Virt35af32 depends on A (because it extends Foo from A)
      // * A depends on Virt35af32 (because it contains a macro expansion referring to FooMock from Virt35af32)
      //
      // after compiling A.scala, sbt will notice that it has a new source file named Virt35af32.
      // it will also think that this file hasn't yet been compiled and since A depends on it
      // it will think that A needs to be recompiled.
      //
      // recompilation will lead to another macro expansion. that another macro expansion might choose to create a fresh mock,
      // producing another virtual file, say, Virtee509a, which will again trick sbt into thinking that A needs a recompile,
      // which will lead to another macro expansion, which will produce another virtual file and so on
      if (exists && !source.file.isVirtual) _depends += symbol
    }

    /** so we can relink
     */
    private[this] val _defined = mutable.HashSet[Symbol]()
    @deprecated("Not supported", "2.12.9")
    def defined = if (exists && !source.file.isVirtual) _defined else mutable.HashSet[Symbol]()

    /** Synthetic definitions generated by namer, eliminated by typer.
     */
    object synthetics {
      private val map = mutable.AnyRefMap[Symbol, Tree]()
      def update(sym: Symbol, tree: Tree): Unit = {
        debuglog(s"adding synthetic ($sym, $tree) to $self")
        map.update(sym, tree)
      }
      def -=(sym: Symbol): Unit = {
        debuglog(s"removing synthetic $sym from $self")
        map -= sym
      }
      def get(sym: Symbol): Option[Tree] = debuglogResultIf[Option[Tree]](s"found synthetic for $sym in $self", _.isDefined) {
        map get sym
      }
      def keys: Iterable[Symbol] = map.keys
      def clear(): Unit = map.clear()
      override def toString = map.toString
    }

    // namer calls typer.computeType(rhs) on DefDef / ValDef when tpt is empty. the result
    // is cached here and re-used in typedDefDef / typedValDef
    // Also used to cache imports type-checked by namer.
    val transformed = new mutable.AnyRefMap[Tree, Tree]

    /** things to check at end of compilation unit */
    val toCheck = ArrayDeque.empty[CompilationUnit.ToCheck]
    private[nsc] def addPostUnitCheck(check: CompilationUnit.ToCheckAfterUnit): Unit = toCheck.append(check)
    private[nsc] def addPostTyperCheck(check: CompilationUnit.ToCheckAfterTyper): Unit = toCheck.append(check)

    /** The features that were already checked for this unit */
    var checkedFeatures = Set[Symbol]()

    def position(pos: Int) = source.position(pos)

    /** The position of a targeted type check
     *  If this is different from NoPosition, the type checking
     *  will stop once a tree that contains this position range
     *  is fully attributed.
     */
    def targetPos: Position = NoPosition

    /** For sbt compatibility (https://github.com/scala/scala/pull/4588) */
    val icode: mutable.LinkedHashSet[icodes.IClass] = new mutable.LinkedHashSet

    /** Is this about a .java source file? */
    val isJava: Boolean = source.isJava

    override def toString() = source.toString()
  }

  object CompilationUnit {
    sealed trait ToCheck { def apply(): Unit }
    trait ToCheckAfterUnit extends ToCheck
    trait ToCheckAfterTyper extends ToCheck
  }
}
