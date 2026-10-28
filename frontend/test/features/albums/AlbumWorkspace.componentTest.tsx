import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { createSignal } from "solid-js";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { AlbumOpen } from "../../../src/features/albums/open/AlbumOpen";
import { AlbumRequestError, type AlbumClient } from "../../../src/features/albums/model/Album";
import type { Chapter, Workspace } from "../../../src/features/albums/model/Workspace";
import type { AlbumPhotoEvents, PhotoEvent, PhotoInventory } from "../../../src/features/albums/model/Photo";

const album = { id: "album", year: 2026, title: "Saved free-form album title", createdAt: "2026-10-25T12:00:00Z", synopsis: "Saved album synopsis" };
const inventory: PhotoInventory = {
  photos: [],
  counts: { unplaced: 0, total: 0 },
  processing: { state: "idle", unsupportedCount: 0, skippedCount: 0, issues: [] },
};
const events: AlbumPhotoEvents = {
  subscribe: (_id, receive) => {
    receive({ kind: "subscribed" });
    return vi.fn();
  },
};
const vacation: Chapter = {
  id: "vacation",
  kind: "vacation",
  name: "Kyoto",
  countries: ["jp"],
  start: "2026-04-02",
  end: "2026-04-14",
  sourceFolder: "/mnt/saved/Kyoto",
  description: "Spring in Japan",
};
const home: Chapter = {
  id: "home",
  kind: "home",
  name: "Home",
  countries: ["ro"],
  start: "2026-01-01",
  end: "2026-12-31",
  sourceFolder: "/mnt/saved/Home",
  description: "",
};
const photoSlot = { id: "photo-slot", kind: "photo" as const, x: 2, y: 4, width: 23, height: 42 };
const textSlot = { id: "text-slot", kind: "text" as const, x: 50, y: 4, width: 48, height: 92 };
const opening = { id: "album-opening", chapter: null, kind: "album_start" as const, slots: [photoSlot, textSlot] };
const workspace: Workspace = {
  photoInventories: { vacation: inventory, home: inventory },
  year: 2026,
  photoRoot: "/mnt/photos",
  chapters: [vacation, home],
  spreads: [
    opening,
    { id: "vacation-opening", chapter: vacation.id, kind: "chapter_start", slots: [photoSlot, textSlot] },
    { id: "vacation-photo", chapter: vacation.id, kind: "photos_only", slots: [photoSlot] },
    { id: "home-opening", chapter: home.id, kind: "chapter_start", slots: [textSlot] },
  ],
  countries: [
    { id: "ro", name: "Romania", code: "RO" },
    { id: "jp", name: "Japan", code: "JP" },
    { id: "it", name: "Italy", code: "IT" },
  ],
};
const nativeMethods = {
  showModal: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "showModal"),
  close: Object.getOwnPropertyDescriptor(HTMLDialogElement.prototype, "close"),
};
const nativeScroll = Object.getOwnPropertyDescriptor(Element.prototype, "scrollIntoView");

describe("album workspace browser boundary", () => {
  beforeAll(() => {
    vi.stubGlobal(
      "ResizeObserver",
      class {
        observe = vi.fn();
        disconnect = vi.fn();
      },
    );
    Object.defineProperty(Element.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
    HTMLDialogElement.prototype.showModal = function () {
      this.setAttribute("open", "");
      this.querySelector<HTMLElement>("select, input, button")?.focus();
    };
    HTMLDialogElement.prototype.close = function () {
      this.removeAttribute("open");
    };
  });
  afterAll(() => {
    vi.unstubAllGlobals();
    if (nativeScroll === undefined) Reflect.deleteProperty(Element.prototype, "scrollIntoView");
    else Object.defineProperty(Element.prototype, "scrollIntoView", nativeScroll);
    for (const [name, descriptor] of Object.entries(nativeMethods)) {
      if (descriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      else Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    }
  });

  it("should select above-editor chapter-grouped spreads, remember interiors, restore Back and recover invalid selections", async () => {
    const client = buildClient();

    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    await screen.findByRole("heading", { name: "A year in photographs / 2026" });
    const controls = within(screen.getByRole("group", { name: "Workspace controls" }));
    const organization = screen.getByRole("navigation", { name: "Album organization" });
    const links = within(organization).getAllByRole("link");
    const expectedLinks = ["Synopsis", "Kyoto", "Kyoto · Photo spread 1", "Home"];

    expect(screen.getByRole("img", { name: "Synopsis composition" })).toHaveAttribute("viewBox", "0 0 2381.102 765.354");
    expect(screen.getByRole("img", { name: "Synopsis composition" })).toHaveTextContent(album.synopsis);
    expect(screen.getByRole("img", { name: "Synopsis composition" })).toHaveTextContent(album.title);
    expect(controls.getAllByRole("button").map((button) => button.textContent)).toEqual(["Reader", "Spreads", "Cover"]);
    expect(screen.queryByText("Photo")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reader" })).toBeEnabled();
    expect(links.map((link) => link.textContent)).toEqual(expectedLinks);

    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));

    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Kyoto composition" })).toHaveTextContent(vacation.description);
    expect(screen.getByRole("img", { name: "Kyoto composition" })).toHaveTextContent(vacation.name);
    expect(screen.queryByRole("button", { name: /Change chapter/ })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeInTheDocument();
    expect(screen.getByText("Country", { exact: true })).toBeInTheDocument();
    expect(window.location.search).toBe("?part=vacation");

    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 1" }));

    expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Cover" }));

    expect(screen.queryByRole("navigation", { name: "Album organization" })).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Cover: back on the left; front on the right/ })).toHaveAccessibleName(/Saved free-form album title/);
    expect(screen.getByText("by Cosmin, Mara & Iacob")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Spreads" }));

    expect(window.location.search).toBe("?part=vacation-photo");

    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));

    expect(window.location.search).toBe("");

    window.history.replaceState(null, "", "?part=home");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();

    window.history.replaceState(null, "", "?part=cover");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("heading", { name: "Cover" })).toBeInTheDocument();

    window.history.replaceState(null, "", "?part=foreign");
    fireEvent(window, new PopStateEvent("popstate"));

    expect(screen.getByRole("alert")).toHaveTextContent("This album part could not be found.");

    fireEvent.click(screen.getByRole("button", { name: "Go to Synopsis" }));

    expect(screen.getByRole("heading", { name: "Synopsis" })).toBeInTheDocument();
  });

  it.each(["cover", "vacation-opening", "vacation-photo"])(
    "should restore a direct %s selection and collapse only photo controls, retaining above-editor navigation",
    async (part) => {
      window.history.replaceState(null, "", `?part=${part}`);
      render(() => <AlbumOpen id={album.id} client={buildClient()} events={events} />);
      await screen.findByRole("heading", { name: "A year in photographs / 2026" });

      if (part === "cover") {
        fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
        expect(screen.getByRole("heading", { name: "Synopsis" })).toBeInTheDocument();
      }
      const collapse = screen.getByRole("button", { name: "Collapse photo pane" });

      expect(collapse).toHaveTextContent(/^$/);

      fireEvent.click(collapse);

      expect(screen.getByRole("navigation", { name: "Album organization" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Refresh photos" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Expand photo pane" })).toHaveFocus();
      expect(window.location.search).toBe(part === "cover" ? "" : `?part=${part}`);
      fireEvent.click(screen.getByRole("button", { name: "Expand photo pane" }));
      expect(screen.getByRole("button", { name: "Collapse photo pane" })).toHaveFocus();
      expect(screen.getByRole("navigation", { name: "Album organization" })).toBeInTheDocument();
    },
  );

  it.each(["cover", "vacation"])(
    "should read from %s across chapter boundaries and retain the current URL, history and remembered interior on exit",
    async (part) => {
      let fullscreen: Element | null = null;
      const fullscreenDescriptor = Object.getOwnPropertyDescriptor(document, "fullscreenElement");
      const requestDescriptor = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "requestFullscreen");
      const exitDescriptor = Object.getOwnPropertyDescriptor(document, "exitFullscreen");
      Object.defineProperty(document, "fullscreenElement", { configurable: true, get: () => fullscreen });
      Object.defineProperty(HTMLElement.prototype, "requestFullscreen", {
        configurable: true,
        value: function (this: HTMLElement) {
          fullscreen = document.contains(this) ? this : null;
          document.dispatchEvent(new Event("fullscreenchange"));
          return Promise.resolve();
        },
      });
      Object.defineProperty(document, "exitFullscreen", {
        configurable: true,
        value: () => {
          fullscreen = null;
          document.dispatchEvent(new Event("fullscreenchange"));
          return Promise.resolve();
        },
      });
      window.history.replaceState(null, "", `?part=${part}`);
      const view = render(() => <AlbumOpen id={album.id} client={buildClient()} events={events} />);

      try {
        fireEvent.click(await screen.findByRole("button", { name: "Reader" }));
        const exit = await screen.findByRole("button", { name: "Exit reader" });

        if (part === "cover") {
          expect(screen.getByRole("button", { name: "Previous spread" })).toBeDisabled();

          fireEvent.keyDown(exit, { key: "ArrowRight" });

          expect(window.location.search).toBe("");

          fireEvent.click(screen.getByRole("button", { name: "Next spread" }));
        }

        expect(screen.getByRole("img", { name: "Kyoto composition" })).toBeInTheDocument();

        fireEvent.keyDown(exit, { key: "ArrowRight" });

        expect(window.location.search).toBe("?part=vacation-photo");

        fireEvent.keyDown(exit, { key: "ArrowRight" });

        expect(screen.getByRole("img", { name: "Home composition" })).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Next spread" })).toBeDisabled();

        fireEvent.keyDown(exit, { key: "ArrowLeft" });

        expect(window.location.search).toBe("?part=vacation-photo");

        fireEvent.click(exit);

        await waitFor(() => expect(screen.getByRole("button", { name: "Reader" })).toHaveFocus());
        expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();
        expect(screen.queryByText(vacation.sourceFolder, { exact: true })).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole("button", { name: "Cover" }));
        fireEvent.click(screen.getByRole("button", { name: "Spreads" }));

        expect(window.location.search).toBe("?part=vacation-photo");

        window.history.replaceState(null, "", "?part=home");
        fireEvent(window, new PopStateEvent("popstate"));

        expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();
      } finally {
        view.unmount();
        for (const [owner, key, descriptor] of [
          [document, "fullscreenElement", fullscreenDescriptor],
          [HTMLElement.prototype, "requestFullscreen", requestDescriptor],
          [document, "exitFullscreen", exitDescriptor],
        ] as const) {
          if (descriptor === undefined) Reflect.deleteProperty(owner, key);
          else Object.defineProperty(owner, key, descriptor);
        }
      }
    },
  );

  it("should create a missed vacation through catalog keyboard selection and refresh its persisted opening in server order", async () => {
    const empty = { ...workspace, chapters: [], spreads: [opening] };
    const missed = {
      ...vacation,
      id: "missed",
      name: "Rome",
      countries: ["ro", "it"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/nas/custom",
    };
    const saved = {
      ...empty,
      chapters: [missed],
      spreads: [
        opening,
        { ...workspace.spreads[1], id: "missed-opening", chapter: missed.id, kind: "chapter_start" as const, slots: [photoSlot, textSlot] },
      ],
    };
    const client = buildClient({
      workspace: vi.fn().mockResolvedValueOnce(empty).mockResolvedValueOnce(saved),
      createChapter: vi.fn().mockResolvedValue({ id: missed.id }),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });

    fireEvent.click(add);
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Add chapter" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Choose a chapter type.");
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "" } });
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "vacation" } });
    expect(screen.getByRole("button", { name: "Remove Romania" })).toBeInTheDocument();
    expect(screen.getByLabelText("First day")).toHaveValue("2026-01-01");
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: "Rome" } });
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/mnt/photos/2026/selection/Rome");
    fireEvent.input(screen.getByRole("textbox", { name: "Photo folder" }), { target: { value: "/nas/custom" } });
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: " Rome " } });
    const search = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.input(search, { target: { value: "Atlantis" } });
    expect(screen.getByText(/No countries match/)).toBeInTheDocument();
    fireEvent.keyDown(search, { key: "ArrowDown" });
    await Promise.resolve();
    fireEvent.keyDown(search, { key: "Enter" });
    fireEvent.input(search, { target: { value: "it" } });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(screen.getByRole("option", { name: "Italy (IT) ✓" })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(search, { key: "Escape" });
    fireEvent.keyDown(search, { key: "Escape" });
    fireEvent.keyDown(search, { key: "Tab" });
    fireEvent.keyDown(search, { key: "Enter" });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: "2025-12-31" } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: "2026-01-01" } });
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/nas/custom");
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(await screen.findByRole("heading", { name: missed.name })).toBeInTheDocument();
    const expectedDetails = {
      kind: "vacation",
      name: "Rome",
      countries: ["ro", "it"],
      start: "2025-12-31",
      end: "2026-01-01",
      sourceFolder: "/nas/custom",
    };
    expect(client.createChapter).toHaveBeenCalledWith(album.id, expectedDetails);
    expect(window.location.search).toBe("?part=missed");
    expect(screen.getByRole("img", { name: "Rome composition" })).toBeInTheDocument();
    expect(screen.getByText("Countries", { exact: true })).toBeInTheDocument();
    expect(add).toHaveFocus();
  });

  it("should retain failed inputs, prevent duplicate submission, then keep a successful save distinct from a failed refresh", async () => {
    let rejectSave: (error: Error) => void = () => {
      throw new Error("Save was not requested");
    };
    const pending = new Promise<{ id: string }>((_resolve, reject) => {
      rejectSave = reject;
    });
    const changed = { ...vacation, name: "Earlier Kyoto", start: "2026-01-01", end: "2026-01-01" };
    const client = buildClient({
      workspace: vi
        .fn()
        .mockResolvedValueOnce(workspace)
        .mockRejectedValueOnce(new Error("Refresh offline"))
        .mockResolvedValueOnce({ ...workspace, chapters: [changed, home] }),
      updateChapter: vi.fn().mockReturnValueOnce(pending).mockResolvedValueOnce({ id: vacation.id }),
    });
    window.history.replaceState(null, "", "?part=vacation-photo");
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const edit = await screen.findByRole("button", { name: "Edit chapter details" });

    fireEvent.click(edit);
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(vacation.sourceFolder);
    expect(screen.getByRole("button", { name: "Remove Japan" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Remove Romania" })).not.toBeInTheDocument();
    fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: changed.name } });
    fireEvent.input(screen.getByLabelText("First day"), { target: { value: changed.start } });
    fireEvent.input(screen.getByLabelText("Last day"), { target: { value: changed.end } });
    const countrySearch = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.focus(countrySearch);
    fireEvent.input(countrySearch, { target: { value: "Romania" } });
    fireEvent.blur(countrySearch, { relatedTarget: screen.getByRole("button", { name: "Remove Japan" }) });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toBeDisabled();
    expect(screen.getByRole("option", { name: "Romania (RO)" })).toHaveAccessibleDescription("Saving chapter. Please wait.");
    expect(screen.getByRole("button", { name: "Save chapter" })).toHaveAccessibleDescription("Saving chapter. Please wait.");
    expect(screen.getByRole("button", { name: "Save chapter" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Save chapter" }));
    expect(client.updateChapter).toHaveBeenCalledTimes(1);
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    rejectSave(new AlbumRequestError(400, "Another chapter already uses that source folder"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Another chapter");
    expect(screen.getByRole("textbox", { name: "Location / name" })).toHaveValue(changed.name);
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Chapter saved, but");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add chapter" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Retry workspace" }));

    expect(await screen.findByRole("heading", { name: "Earlier Kyoto · Photo spread" })).toBeInTheDocument();
    expect(window.location.search).toBe("?part=vacation-photo");
    expect(client.updateChapter).toHaveBeenCalledTimes(2);
  });

  it("should use exact Home fields and saved values, and open vacation creation directly after Home exists", async () => {
    const empty = { ...workspace, chapters: [], spreads: [opening] };
    const client = buildClient({ workspace: vi.fn().mockResolvedValueOnce(empty).mockResolvedValue(workspace) });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    expect(screen.queryByRole("textbox", { name: "Location / name" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("First day")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue("/mnt/photos/2026/selection/Home");
    fireEvent.click(screen.getByRole("button", { name: "Remove Romania" }));
    expect(screen.getByRole("combobox", { name: "Search countries" })).toHaveFocus();
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    expect(screen.getByRole("alert")).toHaveTextContent("Select at least one country.");
    const search = screen.getByRole("combobox", { name: "Search countries" });
    fireEvent.focus(search);
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.mouseDown(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO) ✓" }));
    fireEvent.click(screen.getByRole("option", { name: "Romania (RO)" }));
    fireEvent.blur(search, { relatedTarget: screen.getByRole("button", { name: "Remove Romania" }) });
    fireEvent.blur(search, { relatedTarget: screen.getByRole("textbox", { name: "Photo folder" }) });
    fireEvent.blur(search);
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await screen.findByRole("navigation", { name: "Album organization" });
    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
    expect(client.createChapter).toHaveBeenCalledWith(album.id, { kind: "home", countries: ["ro"], sourceFolder: "/mnt/photos/2026/selection/Home" });
    fireEvent.click(await screen.findByRole("link", { name: "Home" }));
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Edit chapter details" })).toBeEnabled();
    });
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("First day")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(home.sourceFolder);
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Edit chapter details" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit chapter details" }));
    fireEvent(screen.getByRole("dialog"), new Event("cancel", { cancelable: true }));
    fireEvent.click(screen.getByRole("button", { name: "Add chapter" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Location / name" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
  });

  it.each([64, 65])("should expose chapter creation capacity at %i persisted interior spreads", async (count) => {
    const spreads = [
      opening,
      ...Array.from({ length: count - 1 }, (_, index) => ({
        id: `spread-${String(index)}`,
        chapter: vacation.id,
        kind: "photos_only" as const,
        slots: [photoSlot],
      })),
    ];
    const finalChapter = { ...vacation, id: "final-chapter", name: "Final chapter" };
    const finalOpening = { ...opening, id: "final-opening", chapter: finalChapter.id, kind: "chapter_start" as const };
    const savedWorkspace = { ...workspace, chapters: [vacation, finalChapter, home], spreads: [...spreads, finalOpening] };
    const client = buildClient({
      workspace: vi
        .fn()
        .mockResolvedValueOnce({ ...workspace, spreads })
        .mockResolvedValue(savedWorkspace),
      createChapter: vi.fn().mockResolvedValue({ id: finalChapter.id }),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    const add = await screen.findByRole("button", { name: "Add chapter" });

    if (count === 65) {
      expect(add).toBeDisabled();
      expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added.");
    } else {
      expect(add).toBeEnabled();
      fireEvent.click(add);
      expect(screen.getByRole("dialog")).toBeInTheDocument();
      fireEvent.input(screen.getByRole("textbox", { name: "Location / name" }), { target: { value: finalChapter.name } });
      fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
      expect(await screen.findByRole("heading", { name: finalChapter.name })).toBeInTheDocument();
      await waitFor(() => {
        expect(screen.getByRole("region", { name: "Album workspace" })).toHaveFocus();
      });
      expect(add).toBeDisabled();
      expect(add).toHaveAccessibleDescription("This album has 65 interior spreads. No more chapters can be added.");
    }
  });

  it("should browse canonical chapter and album photos, apply unselected live changes and Refresh the selected scope", async () => {
    const photo = {
      id: "photo",
      filename: "Kyoto.jpg",
      capturedLocal: "2026-04-02T10:00",
      width: 1200,
      height: 800,
      caption: null,
      thumbnail: { url: "/thumbnails/key/version.jpg", width: 600, height: 400 },
    };
    const vacationPhotos = { ...inventory, photos: [photo], counts: { unplaced: 0, total: 1 } };
    const homePhotos = { ...inventory, photos: [{ ...photo, id: "home-photo", filename: "Home.jpg" }], counts: { unplaced: 1, total: 1 } };
    const savedWorkspace = { ...workspace, photoInventories: { vacation: vacationPhotos, home: homePhotos } };
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const close = vi.fn();
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        return close;
      },
    };
    const client = buildClient({
      workspace: vi.fn().mockResolvedValue(savedWorkspace),
      chapterPhotos: vi
        .fn()
        .mockRejectedValueOnce(new Error("Inventory unavailable"))
        .mockResolvedValue({ ...homePhotos, processing: { ...inventory.processing, state: "running" } }),
    });
    const view = render(() => <AlbumOpen id={album.id} client={client} events={liveEvents} />);

    expect(client.workspace).not.toHaveBeenCalled();
    receive({ kind: "subscribed" });
    await screen.findByRole("img", { name: "Kyoto.jpg" });
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    expect(screen.getByText("1 unplaced / 2 total")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "Kyoto" }));
    expect(screen.queryByRole("img", { name: "Home.jpg" })).not.toBeInTheDocument();
    const refresh = screen.getByRole("button", { name: "Refresh photos" });
    refresh.focus();
    fireEvent.click(refresh);
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledExactlyOnceWith(vacation.id);
    });
    receive({ kind: "inventory-changed", chapterId: home.id });
    await waitFor(() => {
      expect(client.chapterPhotos).toHaveBeenCalledWith(home.id, expect.any(AbortSignal));
    });
    expect(refresh).toHaveFocus();
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(screen.getByRole("link", { name: "Synopsis" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retry inventory for Home" }));
    await waitFor(() => expect(within(screen.getByRole("region", { name: "Processing Home" })).getByText(/running/)).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    expect(screen.getByRole("img", { name: "Home.jpg" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh photos" }));
    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledTimes(3);
    });
    receive({ kind: "interrupted" });
    expect(screen.getByText(/Live updates interrupted/)).toBeInTheDocument();
    receive({ kind: "subscribed" });
    await waitFor(() => expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument());
    expect(client.workspace).toHaveBeenCalledTimes(2);
    expect(client.open).toHaveBeenCalledOnce();
    expect(window.location.search).toBe("?part=cover");
    view.unmount();
    expect(close).toHaveBeenCalledOnce();
  });

  it("should keep a chapter saved after scheduling failure and retry only photo Refresh", async () => {
    const emptyWorkspace = { ...workspace, chapters: [], spreads: [opening], photoInventories: {} };
    const savedWorkspace = { ...workspace, chapters: [home], photoInventories: { home: inventory } };
    const client = buildClient({
      workspace: vi.fn().mockResolvedValueOnce(emptyWorkspace).mockResolvedValue(savedWorkspace),
      createChapter: vi.fn().mockResolvedValue({ id: home.id, scheduling: "failed" }),
    });
    render(() => <AlbumOpen id={album.id} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    fireEvent.click(await screen.findByRole("button", { name: "Retry Refresh for Home" }));

    await waitFor(() => {
      expect(client.refreshPhotos).toHaveBeenCalledExactlyOnceWith(home.id);
    });
    expect(client.createChapter).toHaveBeenCalledOnce();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Home" })).toBeInTheDocument();
  });

  it("should leave a superseding album untouched when an old chapter save completes after workspace exit", async () => {
    let finishSave: (result: { id: string; scheduling: "queued" }) => void = () => {
      throw new Error("Save not started");
    };
    const pending = new Promise<{ id: string; scheduling: "queued" }>((resolve) => {
      finishSave = resolve;
    });
    const emptyWorkspace = { ...workspace, chapters: [], spreads: [opening], photoInventories: {} };
    const client = buildClient({
      workspace: vi
        .fn()
        .mockResolvedValueOnce(emptyWorkspace)
        .mockResolvedValue({ ...emptyWorkspace, year: 2027 }),
      createChapter: vi.fn().mockReturnValue(pending),
    });
    const [id, changeAlbum] = createSignal(album.id);
    render(() => <AlbumOpen id={id()} client={client} events={events} />);
    fireEvent.click(await screen.findByRole("button", { name: "Add chapter" }));
    fireEvent.change(screen.getByRole("combobox", { name: "Chapter type" }), { target: { value: "home" } });
    fireEvent.submit(screen.getByRole("form", { name: "Chapter details" }));
    await waitFor(() => {
      expect(client.createChapter).toHaveBeenCalledOnce();
    });
    changeAlbum("other");
    await screen.findByRole("heading", { name: "A year in photographs / 2027" });
    finishSave({ id: "old-chapter", scheduling: "queued" });
    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(client.workspace).toHaveBeenCalledTimes(2);
    expect(window.location.search).toBe("");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("should navigate First, Previous, Next and Last above the editor with accessible endpoint context and no wrapping", async () => {
    let receive: (event: PhotoEvent) => void = () => {
      throw new Error("Not subscribed");
    };
    const liveEvents: AlbumPhotoEvents = {
      subscribe: (_id, listener) => {
        receive = listener;
        receive({ kind: "subscribed" });
        return vi.fn();
      },
    };
    const freshWorkspace = {
      ...workspace,
      chapters: workspace.chapters.map((chapter) => ({ ...chapter })),
      spreads: workspace.spreads.map((spread) => ({ ...spread })),
    };
    const client = buildClient({ workspace: vi.fn().mockResolvedValueOnce(workspace).mockResolvedValue(freshWorkspace) });
    render(() => <AlbumOpen id={album.id} client={client} events={liveEvents} />);
    const first = await screen.findByRole("button", { name: "First spread" });
    const previous = screen.getByRole("button", { name: "Previous workspace spread" });
    const next = screen.getByRole("button", { name: "Next workspace spread" });
    const last = screen.getByRole("button", { name: "Last spread" });
    const addSpread = screen.getByRole("button", { name: "Add spread" });

    expect(addSpread).toBeDisabled();
    expect(addSpread).toHaveAccessibleDescription("Adding spreads is not available yet.");
    expect(screen.getByText("Adding spreads is not available yet.")).toBeInTheDocument();
    expect(first).toBeDisabled();
    expect(previous).toBeDisabled();
    expect(first).toHaveAccessibleDescription("Spread 1 / 4");
    fireEvent.click(next);
    expect(window.location.search).toBe("?part=vacation");
    expect(screen.getByRole("link", { name: "Kyoto" })).toHaveAttribute("aria-current", "page");
    fireEvent.click(last);
    expect(window.location.search).toBe("?part=home");
    expect(next).toBeDisabled();
    expect(last).toHaveAccessibleDescription("Spread 4 / 4");
    fireEvent.click(previous);
    expect(window.location.search).toBe("?part=vacation-photo");
    fireEvent.click(first);
    expect(window.location.search).toBe("");
    const chapter = screen.getByRole("link", { name: "Kyoto" });
    fireEvent.click(chapter);
    chapter.focus();
    receive({ kind: "subscribed" });
    await waitFor(() => {
      expect(client.workspace).toHaveBeenCalledTimes(2);
    });
    await waitFor(() => {
      expect(screen.queryByText(/Catching up/)).not.toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Kyoto" })).toBe(chapter);
    expect(chapter).toHaveFocus();
  });
});

function buildClient(overrides: Partial<AlbumClient> = {}): AlbumClient {
  return {
    list: vi.fn(),
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    open: vi.fn().mockResolvedValue(album),
    workspace: vi.fn().mockResolvedValue(workspace),
    chapterPhotos: vi.fn().mockResolvedValue(inventory),
    refreshPhotos: vi.fn().mockResolvedValue("queued"),
    createChapter: vi.fn().mockResolvedValue({ id: home.id, scheduling: "queued" }),
    updateChapter: vi.fn().mockResolvedValue({ id: vacation.id, scheduling: "not-requested" }),
    ...overrides,
  };
}
