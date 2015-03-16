package storrent.client

import storrent.Peer

/**
 * User: zhaoyao
 * Date: 3/16/15
 * Time: 18:41
 */
case class TrackerResponse(interval: Int,
                           peers: List[Peer],
                           complete: Option[Int] = None,
                           incomplete: Option[Int] = None)
