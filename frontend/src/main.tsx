import { render } from "solid-js/web";
import { App } from "./app/App";
import { makeHttpAlbumClient } from "./features/albums/api/HttpAlbumClient";
import { makeAlbumPhotoEvents } from "./features/albums/api/AlbumPhotoEvents";

const target = document.getElementById("app");
if (target === null) {
  throw new Error("The frontend entry point is missing #app.");
}

const client = makeHttpAlbumClient(fetch);
const events = makeAlbumPhotoEvents((url) => new EventSource(url));
render(() => <App client={client} events={events} />, target);
