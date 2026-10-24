import { render } from "solid-js/web";
import { App } from "./app/App";
import { makeHttpAlbumClient } from "./features/albums/api/HttpAlbumClient";

const target = document.getElementById("app");
if (target === null) {
  throw new Error("The frontend entry point is missing #app.");
}

const client = makeHttpAlbumClient(fetch);
render(() => <App client={client} />, target);
