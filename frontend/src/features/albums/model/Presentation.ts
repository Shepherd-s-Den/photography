import { AlbumRequestError, maximumAlbumYear, minimumAlbumYear, type AlbumPage } from "./Album";

export function validateYear(value: string): string | undefined {
  const year = Number(value);
  return /^[0-9]{4}$/.test(value.trim()) && year >= minimumAlbumYear && year <= maximumAlbumYear
    ? undefined
    : `Choose a year from ${minimumAlbumYear.toString()} to ${maximumAlbumYear.toString()}.`;
}

export function failureMessage(error: unknown): string {
  return error instanceof AlbumRequestError ? error.message : "The request could not be completed. Please try again.";
}

export function isMissingAlbum(error: unknown): boolean {
  return error instanceof AlbumRequestError && error.status === 404;
}

export function pagination(page: AlbumPage, loading: boolean) {
  return {
    previous: loading ? "Albums are loading." : page.page <= 1 ? "Already on the first page." : undefined,
    next: loading ? "Albums are loading." : page.page >= page.totalPages ? "Already on the last page." : undefined,
  };
}
