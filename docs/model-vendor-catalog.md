# 모델 제작사 카탈로그

PROJ-319의 개요 modelVendor는 모델 제작사를 뜻한다. 호출 공급자·계약 벤더·제품 매핑·가격 대체 모델과 별개다.

권위 파일은 `apps/dashboard-api/src/main/resources/catalog/model-vendors.tsv`다. 가격 카탈로그를 런타임에 읽지 않는다. 기존 Codeburn 0.9.24 표의 명시적 Claude/OpenAI 모델 이름과 CurrentPublicPrices의 직접 확인 모델을 초기 목록으로 고정했다. 가격 대체 별칭 codex-auto-review는 제외한다. PROJ-312에서 다룬 gpt-6.1-sol 및 claude-haiku-5-5도 명시적으로 포함한다. 가격 유무·값·부분 관측 여부는 제작사 조회에 영향을 주지 않는다.

새 이름은 제작사 근거를 검토한 뒤 이 목록에 추가한다. 접두사만 일치하는 미등록 모델, 누락 이름은 null이다. provider가 있는 ID라도 이름이 목록에 없으면 null이며, unknown ID의 제품명으로 추정하지 않는다. modelId를 바꾸거나 원본·snapshot을 갱신하지 않고 응답 조립 시 모델명을 찾아 매핑한다.
