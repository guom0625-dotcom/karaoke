# karaoke

테슬라 차량용 노래방 안드로이드 앱. 전체 설계와 확정/폐기된 결정은 `docs/DESIGN.md` 참고 (폐기안 재제안 금지).

- 빌드는 GitHub Actions에서만 수행 (`.github/workflows/build.yml`). main 푸시 → 서명된 release APK → GitHub Releases `v1.0.<run_number>`
- 개발 셸은 루팅된 안드로이드 폰의 우분투(aarch64)이며, 호스트 폰이 아님. 로컬 Gradle 빌드는 하지 않음
- 서명 키는 저장소에 두지 않음. Secrets: `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`
