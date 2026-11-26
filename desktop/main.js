const { app, BrowserWindow, shell, ipcMain, dialog } = require("electron");
const path = require("path");
const fs = require("fs");
let draftFolder = null;

const ALLOWED = /^(https:\/\/wa\.me\/|https:\/\/api\.whatsapp\.com\/|whatsapp:\/\/|mailto:)/i;

function createWindow() {
  const win = new BrowserWindow({
    width: 1280,
    height: 860,
    title: "Names Miner",
    backgroundColor: "#F6F4EF",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true
    }
  });
  win.setMenuBarVisibility(false);
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (ALLOWED.test(url)) shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e) => e.preventDefault());
  win.loadFile(path.join(__dirname, "index.html"));
}

ipcMain.handle("save-files", async (e, files) => {
  if (!Array.isArray(files) || !files.length) return { ok: false };
  if (!draftFolder || !fs.existsSync(draftFolder)) {
    const win = BrowserWindow.fromWebContents(e.sender);
    const r = await dialog.showOpenDialog(win, { title: "Choose a folder for the email drafts", properties: ["openDirectory", "createDirectory"] });
    if (r.canceled || !r.filePaths.length) return { ok: false };
    draftFolder = r.filePaths[0];
  }
  for (const f of files) {
    const safe = String(f.name || "draft.eml").replace(/[\\/:*?"<>|]+/g, "_").slice(0, 120);
    fs.writeFileSync(path.join(draftFolder, safe), Buffer.from(String(f.base64 || ""), "base64"));
  }
  shell.openPath(draftFolder);
  return { ok: true, folder: draftFolder };
});

ipcMain.on("open-external", (_e, url) => {
  if (typeof url === "string" && ALLOWED.test(url)) shell.openExternal(url);
});

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
