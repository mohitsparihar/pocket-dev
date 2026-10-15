declare module 'node-pty' {
  interface IPty {
    pid: number;
    write(data: string): void;
    resize(cols: number, rows: number): void;
    kill(signal?: string): void;
    onData(callback: (data: string) => void): void;
    onExit(callback: (exitCode: { exitCode: number; signal?: number }) => void): void;
  }
  interface IptyForkOptions {
    name?: string;
    cols?: number;
    rows?: number;
    cwd?: string;
    env?: Record<string, string>;
  }
  export function spawn(file: string, args: string[], options: IptyForkOptions): IPty;
}
