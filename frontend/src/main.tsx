import { render } from "solid-js/web";
import { App } from "./app/App";

const target = document.getElementById("app");
if (target === null) {
  throw new Error("The frontend entry point is missing #app.");
}

render(() => <App />, target);
