package org.improving.scalify

import Scalify._
import org.eclipse.jdt.core._
import org.eclipse.jdt.core.dom

case class ParameterList(val svds: List[dom.SingleVariableDeclaration]) {
	val params: List[Parameter] = svds.map(_.snode.asInstanceOf[Parameter])
	def emitList: List[Emission] = params.map(_.emit)
	def emitOriginalList: List[Emission] = params.map(_.emitOriginalName)
	def emitPrimaryList: List[Emission] = params.map(_.emitPrimary)
	def emitRenamings: List[Emission] = {
		val renamings = params.map(_.emitRenaming).filter(_ != Nil)
		if (renamings == Nil) Nil
		else renamings ::: List(List(NL))
	}
}

class Parameter(override val node: dom.SingleVariableDeclaration, val method: dom.MethodDeclaration) extends VariableDeclaration(node)
{
	require(vb.isParameter)
	override def needsType: Boolean = true
	lazy val SingleVariableDeclaration(modifiers, jtype, isVarargs, name, dims, init) = node
	
	def collisionSearchScope = method
	// def collisionSearchScope = if (method.isConstructor) method.dtype else method
	def useAlternateName = isUsedInAssignment(collisionSearchScope)
	def emitRenaming: Emission = if (useAlternateName) VAR ~ name ~ EQUALS ~ emitAlternateName ~ NL else Nil
	def isVar: Emission = {
		val mb = vb.getDeclaringMethod
		mb.findMethodDeclaration match {
			case Some(x) if x.isConstructor => x.snode match {
				case x: Constructor if x.isPrimary => VAR
				case _ => Nil
			}
			case _ => Nil
		}
	}
	
	override def emitDirect: Emission = {
		val aName = if (useAlternateName) emitAlternateName else name.emit	
		emitWithName(aName)
	}
	def emitOriginalName: Emission = emitWithName(name.emit)
	def emitPrimary: Emission = VAR ~ emitDirect
	
	private def emitWithName(aName: Emission): Emission = 
		aName ~ COLON ~ arrayWrap(dims)(jtype.emitDirect(node)) ~ emitCond(isVarargs, NOS ~ Emit("*"))
			
	// looks under the supplied root for assignments to this name
	private def isUsedInAssignment(root: ASTNode): Boolean = {		
		// log.trace("Testing if %s is assigned to under %s", name, root.id)
		def opDoesModify(op: String) = (op == "++" || op == "--")
		
		root.descendantExprs exists {
			case Assignment(lhs: dom.SimpleName, _, _) if compareSimpleNames(lhs, name) => true
			case PostfixExpression(lhs: dom.SimpleName, _) if compareSimpleNames(lhs, name) => true
			case PrefixExpression(JavaOp(op), lhs: dom.SimpleName) if compareSimpleNames(lhs, name) && opDoesModify(op) => true
			case _ => false
		}
	}
}

class Field(override val node: dom.VariableDeclarationFragment) extends VariableDeclaration(node)
{
	require(vb.isField)
	override def emitDefaultInitializer: Emission = EQUALS ~ ifDims(UNDERSCORE)

	lazy val VariableDeclarationFragment(name, dims, init) = node
	lazy val FieldDeclaration(_, modifiers, jtype, _) = parent	
}

class LocalVariable(node: dom.VariableDeclaration, val method: dom.MethodDeclaration) extends VariableDeclaration(node)
{
	require (!vb.isField && !vb.isParameter)
	override def emitDefaultInitializer: Emission = EQUALS ~ ifDims(jtype.emitDefaultValue)
	
	lazy val (modifiers, jtype, name, dims, init) = node match {
		case SingleVariableDeclaration(m, j, _, n, d, i) => (m, j, n, d, i)
		case VariableDeclarationFragment(n, d, i) => parent match {
			case VariableDeclarationExpression(m, j, _) => (m, j, n, d, i)
			case VariableDeclarationStatement(m, j, _) => (m, j, n, d, i)
			case FieldDeclaration(_, m, j, _) => (m, j, n, d, i)
		}
	}
	
	override def emitDirect: Emission = {
		log.trace("LocalVariable emitDirect: %s %s", name, modifiers)
		super.emitDirect
	}
}

//         VariableDeclarationStatement => char[] modifierFlags, posFlags={0}, negFlags={0};
//           VariableDeclarationFragment => negFlags={0}
//             ArrayInitializer => {0}
//               NumberLiteral => 0
//             SimpleName => negFlags
//           VariableDeclarationFragment => posFlags={0}
//             ArrayInitializer => {0}
//               NumberLiteral => 0
//             SimpleName => posFlags


abstract class VariableDeclaration(override val node: dom.VariableDeclaration)
extends Node(node) with VariableBound with NamedDecl
{
	override def toString: String = name.fqname + (if (vb.isField) "" else " (local)")
	override def ppString = Some(toString)
	
	def vb = node.resolveBinding
	def emitDefaultInitializer: Emission = Nil
	// require(vb != null)
	
	val modifiers: List[dom.IExtendedModifier]
	val jtype: dom.Type
	val name: dom.SimpleName
	val dims: Int
	val init: Option[dom.Expression]
	
	// so far the logic is: if there's no initializer, it must be a var because
	// unlike java we can't declare a final and assign to it later.
	// Otherwise, if it's final it's a val.
	def isVal: Boolean = isFinal && !init.isEmpty
	
	protected def ifDims(x: Emission): Emission = if (dims > 0) NULL else x
	protected def needsType: Boolean = {
		val retVal = 
			init.isEmpty || 
			!init.get.tb.isEqualTo(jtype.tb) ||
			init.get.tb.isFactoryType ||
			(jtype.tb.isArray && jtype.tb.getElementType.getName == "char")

		// if (retVal) log.trace("Annotating type in %s: %s != %s", 
		// 				toString, init.map(_.tb.getKey).getOrElse("<none>"), jtype.tb.getKey)
			
		retVal
	}

	def emitDirect: Emission = 
		if (needsType) name ~ COLON ~ arrayWrap(dims)(jtype.emitDirect(node)) ~ emitOpt(init, EQUALS ~ _, emitDefaultInitializer)
		else name ~ EQUALS ~ init.get
}
