package storrent.cmd

import storrent.tracker.Stracker

/**
 * User: zhaoyao
 * Date: 4/21/15
 * Time: 10:56
 */
object StartTracker extends App {

  Stracker.start("0.0.0.0", args(0).toInt)

}
