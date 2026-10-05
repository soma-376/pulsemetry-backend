#!/usr/bin/env python3
"""API 문서의 정적 검증. 서버·DB를 실행하거나 수정하지 않는다.

python3 tools/check-api-docs.py [--typescript-output /tmp/api-docs.ts]
TypeScript 출력은 기존 tsc로 추가 검사할 수 있는 문서용 선언 모음이다.
"""
from __future__ import annotations
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
API = ROOT / 'docs/api'
ERRORS: list[str] = []

def fail(message: str) -> None:
    ERRORS.append(message)

def without_comments(text: str) -> str:
    return re.sub(r'/\*[\s\S]*?\*/|//[^\n]*', '', text)

def declarations(block: str):
    text = re.sub(r'//[^\n]*', '', block)
    for match in re.finditer(r'\btype\s+(\w+)(?:<\w+>)?\s*=', text):
        stack, quote, end = [], None, match.end()
        while end < len(text):
            char = text[end]
            if quote:
                if char == quote and text[end - 1] != '\\':
                    quote = None
            elif char in ('"', "'"):
                quote = char
            elif char in '{[<(':
                stack.append(char)
            elif char in '}]>)' and stack:
                stack.pop()
            elif char == ';' and not stack:
                break
            end += 1
        if end == len(text):
            fail(f'{match[1]}: 타입 선언이 ;로 끝나지 않음')
        yield match[1], text[match.start():end + 1].strip()

def anchors(text: str) -> set[str]:
    found = set(re.findall(r'<a id="([^"]+)"', text))
    counts: Counter[str] = Counter()
    fence = False
    for line in text.splitlines():
        if line.startswith('```'):
            fence = not fence
        if fence:
            continue
        match = re.match(r'^#{1,6} (.+?)\s*$', line)
        if not match:
            continue
        slug = ''.join(c for c in match[1].lower() if c in '-_ ' or unicodedata.category(c)[0] in 'LN').replace(' ', '-')
        n = counts[slug]
        counts[slug] += 1
        found.add(slug + (f'-{n}' if n else ''))
    return found

def source_endpoints() -> set[tuple[str, str, str]]:
    result = set()
    annotation = re.compile(r'@(Get|Post|Put|Patch|Delete|Request)Mapping\b(?:\s*\(([\s\S]*?)\))?')
    for server in ('enrollment-api', 'dashboard-api'):
        for path in (ROOT / 'apps' / server / 'src/main/kotlin').rglob('*.kt'):
            text = without_comments(path.read_text())
            # Isolate controller class bodies; configuration/exception advice are not routes.
            for cls in re.finditer(r'@RestController\b([\s\S]*?)\bclass\s+\w+', text):
                prefix = ''
                base = re.search(r'@RequestMapping\("([^"]+)"\)', cls[1])
                if base:
                    prefix = base[1]
                end = text.find('@RestController', cls.end())
                body = text[cls.end():end if end >= 0 else len(text)]
                for mapping in annotation.finditer(body):
                    kind, args = mapping[1], mapping[2] or ''
                    # All quoted strings in current mapping arguments are paths.
                    paths = re.findall(r'"([^"\n]*)"', args) or ['']
                    methods = re.findall(r'RequestMethod\.(\w+)', args) if kind == 'Request' else [kind.upper()]
                    if not methods:
                        fail(f'{path.relative_to(ROOT)}: HTTP method 없는 메서드 매핑을 수동 확인해야 함')
                    for method in methods:
                        for route in paths:
                            if route and not route.startswith('/'):
                                fail(f'{path.relative_to(ROOT)}: 매핑 파서가 해석하지 못한 값 {route}')
                            result.add((server, method, prefix + route))
    oidc = ROOT / 'apps/enrollment-api/src/main/kotlin/com/team376/pulsemetry/enrollment/auth/OidcLoginConfig.kt'
    text = oidc.read_text()
    for route in ('/api/v1/auth/oidc/authorize', '/api/v1/auth/oidc/callback/*'):
        if f'"{route}"' not in text:
            fail(f'OIDC 필터 경로 변경: {route}')
    result.add(('enrollment-api', 'GET', '/api/v1/auth/oidc/authorize'))
    result.add(('enrollment-api', 'GET', '/api/v1/auth/oidc/callback/{registrationId}'))
    return result

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--typescript-output', type=Path)
    args = parser.parse_args()
    files = sorted(API.rglob('*.md'))
    numbers = []
    index = (API / 'README.md').read_text()
    endpoints, definitions, references = [], {}, set()
    json_count = 0
    example_declarations = []
    for path in files:
        text = path.read_text()
        if text.count('```') % 2:
            fail(f'{path.name}: 코드 블록 불균형')
        routes = re.findall(r'<!-- endpoint: (\S+) (\S+) (\S+) -->', text)
        endpoints.extend(tuple(m) for m in routes)
        if path.parent == API / 'endpoints':
            number = re.match(r'^(\d{2,})-[a-z0-9-]+\.md$', path.name)
            if not number or len(routes) != 1:
                fail(f'{path.name}: 번호 파일명과 단일 API 매핑이 필요함')
            else:
                numbers.append(number[1])
                _, method, route = routes[0]
                if not text.startswith(f'# {number[1]} {method} `{route}`\n'):
                    fail(f'{path.name}: 제목의 번호·메서드·경로 불일치')
            if f'(endpoints/{path.name})' not in index:
                fail(f'{path.name}: 전체 API 목록 링크 누락')
            if '## Response' not in text:
                fail(f'{path.name}: Response 제목 누락')
            if re.search(r'### (?:Path|Query|Headers|Body)\n\n(?:없음\.|본문 없음\.|필수 인증 헤더 없음\.)', text):
                fail(f'{path.name}: 사용하지 않는 Request 항목은 생략해야 함')
        elif routes:
            fail(f'{path.name}: API 매핑은 endpoints 파일에서만 정의해야 함')
        for language, block in re.findall(r'```([^\n]*)\n([\s\S]*?)```', text):
            if language == 'json':
                try:
                    json.loads(block)
                    json_count += 1
                except json.JSONDecodeError as error:
                    fail(f'{path.name}: JSON 예시 오류 {error}')
            if language == 'ts':
                for name, declaration in declarations(block):
                    if name in definitions:
                        fail(f'{path.name}: {name} 중복 정의 ({definitions[name][0].name})')
                    definitions[name] = (path, declaration)
                clean = re.sub(r'//[^\n]*|"[^"\n]*"', '', block)
                references.update(re.findall(r'\b[A-Z][A-Za-z0-9_]+\b', clean))
    for example in sorted((API / 'examples').glob('*.json')):
        try:
            value = json.loads(example.read_text())
            json_count += 1
            schema = ''.join(part.title() for part in example.name.split('.')[0].split('-'))
            if schema not in definitions:
                fail(f'{example.name}: 대응하는 응답 스키마 없음 ({schema})')
            example_declarations.append(f'const example{schema}: {schema} = ' + json.dumps(value, ensure_ascii=False) + ';')
        except json.JSONDecodeError as error:
            fail(f'{example.name}: JSON 예시 오류 {error}')
    for name in sorted(references - definitions.keys() - {'Array', 'Record', 'Omit', 'T'}):
        fail(f'정의되지 않은 스키마: {name}')
    for number, count in Counter(numbers).items():
        if count != 1:
            fail(f'API 번호 중복: {number}')
    expected = source_endpoints()
    actual = set(endpoints)
    for item in sorted(expected - actual):
        fail(f'문서 누락: {item}')
    for item in sorted(actual - expected):
        fail(f'구현에 없는 매핑: {item}')
    for item, count in Counter(endpoints).items():
        if count != 1:
            fail(f'엔드포인트 중복({count}): {item}')
    # Local repository files and anchors must resolve. Sibling-repository links require
    # the documented multi-repo checkout and are intentionally not fetched in CI.
    links = 0
    for path in files + [ROOT / 'docs/enrollment-server-spec.md', ROOT / 'docs/dashboard-server-spec.md', ROOT / 'AGENTS.md', ROOT / 'docs/user-auth-operations.md', ROOT / 'docs/frontend-e2e-scenarios.md', ROOT / 'docs/module-map.md']:
        text = re.sub(r'```[^\n]*\n[\s\S]*?```', '', path.read_text())
        for url in re.findall(r'\[[^\]]*\]\(([^\s)]+)\)', text):
            if re.match(r'[a-z]+://|mailto:', url):
                continue
            target, _, anchor = unquote(url).partition('#')
            destination = (path.parent / target).resolve() if target else path
            if not destination.is_relative_to(ROOT):
                continue
            links += 1
            if not destination.exists():
                fail(f'{path.name}: 없는 파일 {url}')
            elif anchor and destination.suffix == '.md' and anchor not in anchors(destination.read_text()):
                fail(f'{path.name}: 없는 앵커 {url}')
    if args.typescript_output:
        args.typescript_output.write_text('\n\n'.join([d[1] for d in definitions.values()] + example_declarations) + '\n')
    if ERRORS:
        print('\n'.join(ERRORS), file=sys.stderr)
        print(f'FAIL: {len(ERRORS)} errors', file=sys.stderr)
        return 1
    print(f'OK: {len(actual)} endpoints, {len(definitions)} schemas, {json_count} JSON examples, {links} local links')
    print('Coverage: Enrollment/Dashboard controller mappings + OIDC filters; no server/DB or sibling-repository validation.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
