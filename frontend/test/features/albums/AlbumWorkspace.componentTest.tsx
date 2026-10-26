import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { AlbumOpen } from "../../../src/features/albums/open/AlbumOpen";
import { AlbumRequestError, type AlbumClient } from "../../../src/features/albums/model/Album";
import type { Chapter, Workspace } from "../../../src/features/albums/model/Workspace";

const album = { id: "album", year: 2026, title: "Saved free-form album title", createdAt: "2026-10-25T12:00:00Z", synopsis: "" };
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
    if (nativeScroll === undefined) Reflect.deleteProperty(Element.prototype, "scrollIntoView");
    else Object.defineProperty(Element.prototype, "scrollIntoView", nativeScroll);
    for (const [name, descriptor] of Object.entries(nativeMethods)) {
      if (descriptor === undefined) Reflect.deleteProperty(HTMLDialogElement.prototype, name);
      else Object.defineProperty(HTMLDialogElement.prototype, name, descriptor);
    }
  });

  it("should select numbered persisted chapter blocks and photo spreads, remember interiors, restore Back and recover invalid selections", async () => {
    const client = buildClient();
    render(() => <AlbumOpen id={album.id} client={client} />);
    await screen.findByRole("heading", { name: "A year in photographs / 2026" });

    expect(screen.getByRole("img", { name: "Album opening composition" })).toHaveAttribute("viewBox", "0 0 2381.102 765.354");
    expect(screen.queryByText("Photo")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Reader" })).not.toBeInTheDocument();
    const organization = screen.getByRole("navigation", { name: "Album organization" });
    const links = within(organization).getAllByRole("link");
    const expectedLinks = ["Album opening", "01 Kyoto 02/04/2026 - 14/04/2026", "Kyoto · Photo spread 1", "02 Home Throughout the year"];

    expect(links.map((link) => link.textContent)).toEqual(expectedLinks);
    fireEvent.click(screen.getByRole("link", { name: /01 Kyoto/ }));
    expect(screen.getByRole("heading", { name: "Kyoto" })).toBeInTheDocument();
    expect(window.location.search).toBe("?part=vacation");
    fireEvent.click(screen.getByRole("link", { name: "Kyoto · Photo spread 1" }));
    expect(screen.getByRole("heading", { name: "Kyoto · Photo spread" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Cover" }));
    expect(screen.queryByRole("navigation", { name: "Album organization" })).not.toBeInTheDocument();
    expect(screen.getByRole("img", { name: /Cover: back on the left; front on the right/ })).toHaveAccessibleName(/Saved free-form album title/);
    expect(screen.getByText("by Cosmin, Mara & Iacob")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
    expect(window.location.search).toBe("?part=vacation-photo");
    fireEvent.click(screen.getByRole("link", { name: "Album opening" }));
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
    fireEvent.click(screen.getByRole("button", { name: "Go to album opening" }));
    expect(screen.getByRole("heading", { name: "Album opening" })).toBeInTheDocument();
  });

  it.each(["cover", "vacation-opening", "vacation-photo"])(
    "should restore a direct %s selection and keep collapsed controls inaccessible",
    async (part) => {
      window.history.replaceState(null, "", `?part=${part}`);
      render(() => <AlbumOpen id={album.id} client={buildClient()} />);
      await screen.findByRole("heading", { name: "A year in photographs / 2026" });

      if (part === "cover") {
        fireEvent.click(screen.getByRole("button", { name: "Spreads" }));
        expect(screen.getByRole("heading", { name: "Album opening" })).toBeInTheDocument();
      }
      const collapse = screen.getByRole("button", { name: "Collapse chapter sidebar" });
      fireEvent.click(collapse);

      expect(screen.queryByRole("navigation", { name: "Album organization" })).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Expand chapter sidebar" })).toHaveFocus();
      expect(window.location.search).toBe(part === "cover" ? "" : `?part=${part}`);
      fireEvent.click(screen.getByRole("button", { name: "Expand chapter sidebar" }));
      expect(screen.getByRole("button", { name: "Collapse chapter sidebar" })).toHaveFocus();
      expect(screen.getByRole("navigation", { name: "Album organization" })).toBeInTheDocument();
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
    render(() => <AlbumOpen id={album.id} client={client} />);
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
    expect(screen.getByRole("status")).toHaveTextContent("No countries match");
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
    render(() => <AlbumOpen id={album.id} client={client} />);
    const edit = await screen.findByRole("button", { name: "Change chapter photo folder" });

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
    render(() => <AlbumOpen id={album.id} client={client} />);
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
    fireEvent.click(await screen.findByRole("link", { name: /02 Home/ }));
    fireEvent.click(screen.getByRole("button", { name: "Change chapter countries" }));
    expect(screen.queryByRole("combobox", { name: "Chapter type" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("First day")).not.toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Photo folder" })).toHaveValue(home.sourceFolder);
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("button", { name: "Change chapter countries" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Change chapter location" }));
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(screen.getByRole("button", { name: "Change chapter period" }));
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
    render(() => <AlbumOpen id={album.id} client={client} />);
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
});

function buildClient(overrides: Partial<AlbumClient> = {}): AlbumClient {
  return {
    list: vi.fn(),
    create: vi.fn(),
    rename: vi.fn(),
    remove: vi.fn(),
    open: vi.fn().mockResolvedValue(album),
    workspace: vi.fn().mockResolvedValue(workspace),
    createChapter: vi.fn().mockResolvedValue({ id: home.id }),
    updateChapter: vi.fn().mockResolvedValue({ id: vacation.id }),
    ...overrides,
  };
}
