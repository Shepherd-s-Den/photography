import { AlbumRequestError, type AlbumPage } from "./Album";

export function validateTitle(value: string): string | undefined {
  return value.trim() === "" ? "Enter an album title." : undefined;
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
