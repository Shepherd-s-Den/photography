import { Show } from "solid-js";
import type { AlbumClient } from "../features/albums/model/Album";
import { AlbumDashboard } from "../features/albums/dashboard/AlbumDashboard";
import { AlbumOpen } from "../features/albums/open/AlbumOpen";
import "./app.css";

export function App(props: { client: AlbumClient }) {
  const id = /^\/albums\/([^/]+)\/?$/.exec(window.location.pathname)?.[1];
  return (
    <>
      <header class="topbar">
        <a class="brand" href="/">
          Album Layout Studio
        </a>
      </header>
      <main class="page">
        <Show when={id} fallback={<AlbumDashboard client={props.client} />}>
          {(albumId) => <AlbumOpen client={props.client} id={albumId()} />}
        </Show>
      </main>
    </>
  );
}
