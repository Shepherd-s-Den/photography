package photography.domain.layout

import photography.domain.album.AlbumId
import java.util.UUID

enum SpineOwner derives CanEqual:
  case Front, Back

enum CoverPanel derives CanEqual:
  case Front, Back

opaque type CoverSlotId = UUID
object CoverSlotId:
  def apply(value: UUID): CoverSlotId = value
  extension (id: CoverSlotId) def value: UUID = id

final case class CoverSlot(id: CoverSlotId)

final case class Cover(
    album: AlbumId,
    spineOwner: SpineOwner,
    front: CoverSlot,
    back: CoverSlot
)

final case class SaalCoverSpecification(
    pageCount: PageCount,
    totalWidthPx: CoverPixels,
    heightPx: CoverPixels,
    spinePx: CoverPixels,
    wrapPx: CoverPixels,
    bleedLeftRightPx: CoverPixels,
    bleedTopBottomPx: CoverPixels
)

final case class CoverGeometry(
    totalWidthPt: InDesignPoints,
    heightPt: InDesignPoints,
    spinePt: InDesignPoints,
    wrapPt: InDesignPoints,
    bleedLeftRightPt: InDesignPoints,
    bleedTopBottomPt: InDesignPoints,
    pageWidthPt: InDesignPoints,
    backStartPt: InDesignPoints,
    spineStartPt: InDesignPoints,
    frontStartPt: InDesignPoints
)

final case class CoverPhotoFrame(
    xPt: InDesignPoints,
    yPt: InDesignPoints,
    widthPt: InDesignPoints,
    heightPt: InDesignPoints
)
