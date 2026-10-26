export const minimumAlbumYear = 1900;
export const maximumAlbumYear = 2100;

export interface AlbumSummary {
  readonly id: string;
  readonly year: number;
  readonly title: string;
  readonly createdAt: string;
}

export interface Album extends AlbumSummary {
  readonly synopsis: string;
}

export interface AlbumPage {
  readonly albums: readonly AlbumSummary[];
  readonly page: number;
  readonly pageSize: number;
  readonly totalAlbums: number;
  readonly totalPages: number;
}

export interface AlbumClient {
  readonly list: (page: number) => Promise<AlbumPage>;
  readonly create: (year: number) => Promise<AlbumSummary>;
  readonly rename: (id: string, title: string) => Promise<AlbumSummary>;
  readonly open: (id: string) => Promise<Album>;
  readonly remove: (id: string) => Promise<void>;
}

export class AlbumRequestError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}
