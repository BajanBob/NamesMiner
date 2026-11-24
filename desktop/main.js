const { app, BrowserWindow, shell, ipcMain } = require("electron");
const path = require("path");

const ALLOWED = /^(https:\/\/wa\.me\/|https:\/\/api\.whatsapp\.com\/|whatsapp:\/\/)/i;

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

ipcMain.on("open-external", (_e, url) => {
  if (typeof url === "string" && ALLOWED.test(url)) shell.openExternal(url);
});

app.whenReady().then(() => {
  createWindow();
  app.on("activate", () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});
app.on("window-all-closed", () => { if (process.platform !== "darwin") app.quit(); });
