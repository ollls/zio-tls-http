package object zhttp {

   //one way only from false to true - it stops all the instances.
   @volatile private var f_terminate = false

   def terminate = f_terminate = true
   def isTerminated = f_terminate
}
