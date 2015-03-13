package storrent.tracker

import storrent.Peer

case class PeerState(peer: Peer,
                     uploaded: Long,
                     downloaded: Long,
                     left: Long,
                     firstSeen: Long,
                     lastSeen: Long,
                     state: String)

class TorrentState(infoHash: String, peers: List[PeerState])