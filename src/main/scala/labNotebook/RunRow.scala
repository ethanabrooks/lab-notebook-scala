package labNotebook

import java.sql.Blob

case class RunRow(
                   checkpoint: Option[Blob],
                   commit: String,
                   config: String,
                   configScript: Option[String],
                   containerId: String,
                   description: String,
                   events: Option[Blob],
                   killScript: String,
                   launchScript: String,
                   name: String,
                 )

