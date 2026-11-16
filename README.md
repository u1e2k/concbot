# ConcBot

Android 端末向けの軽量・拡張可能なスマホ操作自動化アプリです。  
Shizuku や root 権限に依存せず、Android 標準の **AccessibilityService (ユーザー補助サービス)** と **NFC (NDEF)** を活用して画面操作を自動化します。

第 1 弾機能として、**「NFC タグをかざして Wi-Fi テザリング（アクセスポイント）を即座にトグル切り替えする機能」** を提供します。

---

## 主な特徴

- **外部ツール不要 (No Root / No Shizuku)**: Android 標準のユーザー補助 API のみで動作します。
- **3層アーキテクチャによる高い拡張性**: トリガー、司令塔、アクション実行部が完全に分離されており、新しいセンサーや画面操作を容易に追加できます。
- **透明 Activity による高速トリガー**: NFC 検知時は `Theme.NoDisplay` の透明 Activity が一瞬だけ起動し、画面遷移のチラつきを抑えてバックグラウンド感覚で操作を実行します。
- **デバウンス制御**: 3秒以内の連続した NFC タッチによる重複実行を防止します。
- **スマートな UI ノード判定**: Pixel 9a（Android 15 / 16 以降）のテザリング設定画面の階層（1階層トグル／2階層サブ画面トグル）を自動判別して操作します。
- **内蔵 NFC ライター**: 市販の NFC タグ（NTAG213 / NTAG215 等）にワンタップで専用トリガーURIを書き込めます。

---

## アーキテクチャ

```mermaid
flowchart TD
    subgraph TriggerLayer ["1. Trigger層 (入口)"]
        NFC["NFC Tag (NDEF)"] -->|concbot://action/tether_toggle| NfcAct["NfcTriggerActivity (Theme.NoDisplay)"]
        UI["MainActivity (手動テスト)"] --> Dispatcher
        NfcAct -->|デバウンス判定後| Dispatcher
    end

    subgraph DispatcherLayer ["2. Dispatcher層 (司令塔)"]
        Dispatcher["ActionDispatcher"]
        Dispatcher -->|アクション登録 & 実行依頼| ServiceMgr["ConcAccessibilityService"]
    end

    subgraph ExecutionLayer ["3. Action / Execution層 (実行部)"]
        ServiceMgr -->|UIイベント通知 & 実行| Action["AutomationAction (インターフェース)"]
        Action --> Tether["TetherToggleAction"]
        Tether -->|1. 設定画面起動| Settings["TetherSettings"]
        Tether -->|2. ノード探索 & クリック| Switch["Wi-Fiアクセスポイント Switch"]
        Tether -->|3. ハプティクス| Vibe["Vibrator (EFFECT_CLICK)"]
        Tether -->|4. バック復帰| Back["performGlobalAction(BACK)"]
    end
```

### レイヤー責務
1. **Trigger層 (`io.github.u1e2k.concbot.trigger`)**:
   - `NfcTriggerActivity`: NDEF から URI スキーマ（`concbot://action/{action_id}`）を解析し、デバウンス判定後に Dispatcher へ渡して即座に終了。
2. **Dispatcher層 (`io.github.u1e2k.concbot.dispatcher`)**:
   - `ActionDispatcher`: アクション ID に対応する `AutomationAction` を生成し、`ConcAccessibilityService` に実行を委譲。
3. **Action / Execution層 (`io.github.u1e2k.concbot.action`, `io.github.u1e2k.concbot.service`)**:
   - `AutomationAction`: 各自動化操作の共通インターフェース。
   - `TetherToggleAction`: 設定画面の起動、ノード探索、スイッチ切り替え、触覚フィードバック、元画面復帰、タイムアウト処理を制御。
   - `ConcAccessibilityService`: Android OS から画面イベントを受け取り、実行中アクションに仲介。

---

## ターゲット環境

- **端末**: Google Pixel 9a (Google Pixel シリーズ / AOSP・Pixel UI 環境)
- **OS**: Android 10 (API 29) 以上、Android 14 / 15 / 16 推奨
- **開発言語**: Kotlin 1.9+
- **ビルドツール**: Android Gradle Plugin 8.4+ / Gradle 8.7

---

## ビルド & インストール手順

### 1. ビルド
リポジトリ直下で Gradle Wrapper を実行します。

```bash
# Windows
.\gradlew assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

生成された APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

### 2. 端末へのインストール
USB デバッグを有効にした端末を接続し、以下を実行します。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 初期設定 & 使い方

### 1. ユーザー補助の有効化（初回のみ）
1. インストールした **ConcBot** を起動します。
2. **「ユーザー補助設定を開く」** をタップします。
3. 一覧から **「ConcBot」** を選択し、サービスのトグルを **ON** にします。
4. アプリに戻ると、ステータスが `有効 (稼働中)` に更新されます。

### 2. 動作確認（手動テスト）
- **「テザリング切替 (テスト実行)」** をタップします。
- 自動的にテザリング設定画面が一瞬開き、Wi-Fi テザリングがトグル切り替えされ、バイブレーションとともに元の画面へ復帰します。

### 3. NFC タグへの書き込み
1. **「NFCタグ書き込み待機を開始」** をタップします。
2. 市販の NFC タグ（NTAG213 / NTAG215 / NTAG216 など）をスマホ背面の NFC アンテナにかざします。
3. 書き込み成功のバイブとともに完了メッセージが表示されます。
   - 書き込まれる URI: `concbot://action/tether_toggle`

### 4. NFC トリガーによる運用
- スマホがロック解除された状態で NFC タグにかざすだけで、ホーム画面や他アプリを開いていても即座にテザリングの ON / OFF が切り替わります。

---

## 将来のアクション追加方法

新しい自動操作を追加する場合、以下のステップで簡単に拡張できます。

1. `AutomationAction` を実装した新しいクラス（例: `BluetoothToggleAction`）を `action/impl/` に作成。
2. `ActionDispatcher.kt` の `actionFactories` マップにアクション ID とファクトリを登録。
3. NFC タグに `concbot://action/{追加したaction_id}` を書き込むか、UI から dispatch を呼ぶだけで動作します。
