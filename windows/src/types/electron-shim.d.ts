declare module 'electron' {
  export interface App {
    whenReady(): Promise<void>;
    getPath(name: 'userData' | 'documents' | 'temp'): string;
    quit(): void;
    on(event: 'window-all-closed' | 'activate', listener: (...args: unknown[]) => void): void;
  }
  export class BrowserWindow {
    static getAllWindows(): BrowserWindow[];
    constructor(options: {
      width: number;
      height: number;
      minWidth?: number;
      minHeight?: number;
      title?: string;
      webPreferences: {
        preload: string;
        contextIsolation: boolean;
        nodeIntegration: boolean;
        sandbox: boolean;
        webSecurity: boolean;
      };
    });
    loadFile(filePath: string): Promise<void>;
    webContents: {
      setWindowOpenHandler(handler: () => { action: 'deny' | 'allow' }): void;
    };
  }
  export const app: App;
  export const ipcMain: {
    handle(channel: string, listener: (event: unknown, ...args: unknown[]) => Promise<unknown> | unknown): void;
  };
  export const ipcRenderer: {
    invoke(channel: string, ...args: unknown[]): Promise<unknown>;
  };
  export const contextBridge: {
    exposeInMainWorld(apiKey: string, api: Record<string, unknown>): void;
  };
  export const dialog: {
    showOpenDialog(options: {
      properties?: string[];
      filters?: Array<{ name: string; extensions: string[] }>;
    }): Promise<{ canceled: boolean; filePaths: string[] }>;
    showSaveDialog(options: {
      defaultPath?: string;
      filters?: Array<{ name: string; extensions: string[] }>;
    }): Promise<{ canceled: boolean; filePath?: string }>;
  };
  export const safeStorage: {
    isEncryptionAvailable(): boolean;
    encryptString(plainText: string): Buffer;
    decryptString(encrypted: Buffer): string;
  };
}
