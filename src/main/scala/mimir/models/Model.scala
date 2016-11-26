package mimir.models;

import scala.util.Random
import mimir.algebra._

/**
 * Root class for Model objects.
 *
 * Models are one of the primitive building blocks in Mimir.  While
 * VGTerms (defined in the mimir.ctables package) serve to create 
 * placeholders in relational data, Models serve to dictate how these
 * placeholders are filled in.  
 *
 * Each discrete placeholder value in a relation is associated with
 * a single variable.  Variables are identified by an index (idx), and
 * by zero or more argument expressions.  
 *
 * - Indexes allow a single model object to define multiple categories
 *   of variables.  Indexes are not data-dependent: For any given query
 *   there may only ever be a finite number of index values.  However,
 *   variables with different indexes may follow different typing rules.
 * 
 * - Argument expressions allow variables to be dynamically created
 *   based on the data, for example one variable per row.  However, 
 *   variables distinguished only by argument expressions must follow
 *   the same typing rules.
 *
 * In short, the main distinction between indexes and arguments is how
 * the variables interacts with Mimir's typesystem.  Arguments can create
 * an arbitrary number of variable instances per query, but must all
 * follow the same typescheme.  Meanwhile Indexes can create variables 
 * with different types, but there can only be a finite number of indexes
 * in use per query.
 *
 * Models are left intentionally abstract.  For the moment, at least, we 
 * do not try to dictate whether the model should be defined using 
 * probability theory, fuzzy logic, belief theory, or any other type of
 * principled mechanism.  Rather, the interface simply requires the model
 * to be able to generate a most likely `bestGuess` value, and be able
 * to draw `sample` values of possible outputs.
 * 
 * That said, there are specific specific classes of model that are
 * intended to fulfil specific roles (see ModelRegistry for more details). 
 * Models that follow these patterns are expected to conform to specific
 * conventions in terms of their types, how they use arguments, and how
 * they are constructed.
 */
abstract class Model(val name: String) {
  /**
   * Infer the type of the model from the types of the inputs
   * @param argTypes    The types of the arguments the the VGTerm
   * @return            The type of the value returned by this model
   */
  def varType        (idx: Int, argTypes:List[Type.T]): Type.T

  /**
   * Generate a best guess for a variable represented by this model.
   * @param idx         The index of the variable family to generate a best guess for
   * @param args        The skolem identifier for the specific variable to generate a best guess for
   * @return            A primitive value representing the best guess value.
   */
  def bestGuess      (idx: Int, args: List[PrimitiveValue]):  PrimitiveValue
  /**
   * Generate a sample from the distribution of a variable represented by this model.
   * @param idx         The index of the variable family to generate a sample for
   * @param randomness  A java.util.Random to use when generating the sample (pre-seeded)
   * @param args        The skolem identifier for the specific variable to generate a sample for
   * @return            A primitive value representing the generated sample
   */
  def sample         (idx: Int, randomness: Random, args: List[PrimitiveValue]):  PrimitiveValue
  /**
   * Generate a human-readable explanation for the uncertainty captured by this model.
   * @param idx   The index of the variable family to explain
   * @param args  The skolem identifier for the specific variable to explain
   * @return      A string reason explaining the uncertainty in this model
   */
  def reason         (idx: Int, args: List[Expression]): (String)

  /**
   * Encode the model for persistence to disk/the database
   * @return      A 2-tuple including the serialized encoding, and the name of 
   *              a deserializer to use when decoding the encoding.
   */
  def serialize(): (Array[Byte], String) =
  {
    val out = new java.io.ByteArrayOutputStream()
    val objects = new java.io.ObjectOutputStream(out)
    objects.writeObject(this)

    return (out.toByteArray, "JAVA")
  }

}