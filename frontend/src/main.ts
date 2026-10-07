import { mount } from "svelte";
import App from "./app/App.svelte";

const target = document.getElementById("app");
if (target === null) {
  throw new Error("The frontend entry point is missing #app.");
}

mount(App, { target });
