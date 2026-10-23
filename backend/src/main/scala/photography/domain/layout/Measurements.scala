package photography.domain.layout

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

type InDesignPoints = Double :| (Greater[-1.7976931348623157e308] & Less[1.7976931348623157e308])
type Zoom           = Double :| (Positive & Less[1.7976931348623157e308])
