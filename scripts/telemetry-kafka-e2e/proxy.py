"""로컬 E2E 의 TCP 프록시. ALB 가 OTLP 대상 그룹을 바꾸는 것을 흉내 낸다.

인자는 `<listen port>=<host:port>` 또는 `<listen port>=@<파일>` 이다. 파일 형식이면 연결마다 그 파일의 `host:port` 를 읽고,
파일 내용이 바뀌면 그 대상으로 열려 있던 연결을 모두 닫는다 — 데몬의 HTTP keep-alive 연결이 다음 요청부터 새 대상으로
다시 붙게 하기 위해서다(ALB 의 대상 전환과 같다). 바이트를 그대로 넘기고 본문·헤더를 읽거나 남기지 않는다.
"""
import asyncio
import sys


def read_target(spec: str) -> str:
    if spec.startswith("@"):
        try:
            with open(spec[1:], encoding="utf-8") as f:
                return f.read().strip()
        except OSError:
            return ""
    return spec


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        writer.close()


class Listener:
    def __init__(self, spec: str) -> None:
        self.spec = spec
        self.current = read_target(spec)
        self.connections: set[asyncio.StreamWriter] = set()

    async def handle(self, client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter) -> None:
        target = read_target(self.spec)
        try:
            host, port = target.rsplit(":", 1)
            upstream_reader, upstream_writer = await asyncio.open_connection(host, int(port))
        except (OSError, ValueError):
            client_writer.close()
            return
        self.connections.update({client_writer, upstream_writer})
        try:
            await asyncio.gather(pipe(client_reader, upstream_writer), pipe(upstream_reader, client_writer))
        finally:
            self.connections.difference_update({client_writer, upstream_writer})

    async def watch(self) -> None:
        if not self.spec.startswith("@"):
            return
        while True:
            await asyncio.sleep(0.5)
            target = read_target(self.spec)
            if target != self.current:
                self.current = target
                for writer in list(self.connections):
                    writer.close()
                print(f"proxy: 대상 전환 → {target}, 열린 연결 {len(self.connections)} 개를 닫았다", flush=True)


async def main(mappings: list[str]) -> None:
    tasks = []
    for mapping in mappings:
        port, spec = mapping.split("=", 1)
        listener = Listener(spec)
        server = await asyncio.start_server(listener.handle, "127.0.0.1", int(port))
        tasks += [server.serve_forever(), listener.watch()]
    await asyncio.gather(*tasks)


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
