# Rokid Glasses — デプロイ＆ミラーリングガイド

&#x20;

> 初めて使用する方向け | Audit C ハッカソン | 2026年5月30日

***

## パート1 — 開始前に必要なもの

### ハードウェア

- \[ ] Rokid Glasses 本体
- \[ ] Rokid 開発用ケーブル（充電ケーブルではありません — データ通信用ケーブルを Rokid チームに依頼してください）
- \[ ] Hi Rokid App がインストールされた Android スマートフォン
- \[ ] Android Studio がインストールされた Mac ノートパソコン

### 今すぐインストールするソフトウェア

```
# Android Studio が未インストールの場合はインストール
# ダウンロード先: https://developer.android.com/studio

# ADB ツールをインストール（Android Studio に同梱されていますが、個別にインストールすることもできます）
brew install android-platform-tools

# 画面ミラーリング用の scrcpy をインストール
brew install scrcpy

# ADB が動作することを確認
adb version

```

***

## パート2 — Glasses の初回セットアップ

### ステップ1 — Glasses をスマートフォンとペアリングする

1. Android スマートフォンで **Hi Rokid App** を開く
2. **+** をタップしてデバイスを追加する
3. Glasses を装着し、画面に表示されるペアリング手順に従う
4. Glasses を Mac ノートパソコンと同じ Wi-Fi に接続する
   - Hi Rokid App → Settings → WiFi の順に進む
   - 会場の Wi-Fi に接続する

### ステップ2 — Glasses で ADB を有効にする

1. Android スマートフォンで **Hi Rokid App** を開く
2. **Settings** → **Developer Options** の順に進む
3. **Enable ADB** をオンに切り替える
4. Glasses に確認メッセージが表示されたら許可する

### ステップ3 — 開発用ケーブルで Glasses を Mac に接続する

1. **開発用ケーブル**（左テンプルのデータ通信用接点）を使用する
2. Mac の USB ポートに接続する
3. Glasses に「Allow USB debugging」と表示された場合は許可する

### ステップ4 — Glasses が ADB に表示されることを確認する

```
# ターミナルで実行
adb devices

```

想定される出力:

```
List of devices attached
XXXXXXXX    device

```

デバイスが一覧に表示されたら、デプロイの準備は完了です。✅ 何も表示されない場合は、ケーブルと ADB が有効になっているかを確認し、`adb kill-server && adb start-server` を試してください。

***

## パート3 — Audit C アプリを Glasses にデプロイする

### ステップ1 — Android Studio でプロジェクトを開く

1. Android Studio を開く
2. File → Open の順に進み、`/Users/mandokororyotaro/Desktop/trae/auditc/auditc-rokid-android`（Rokid 向け Kotlin プロジェクト）を選択する
3. Gradle の同期が完了するまで待つ（初回は2〜3分かかる場合があります）

### ステップ2 — よくある Gradle の問題を修正する

Gradle の同期に失敗した場合:

```
# プロジェクトを開き直す場合
cd /Users/mandokororyotaro/Desktop/trae/auditc/auditc-rokid-android

# Android Studio で File → Sync Project with Gradle Files を実行

```

エラーを SOLO Coder に貼り付けて修正してください。

### USB 接続のみでバックエンドと通信する場合

Wi-Fi を使用しない場合は、アプリを起動する前に次のコマンドを実行する:

```
adb reverse tcp:8000 tcp:8000

```

Rokid 向けアプリの接続先は `http://127.0.0.1:8000` に設定済みです。

### ステップ3 — 対象デバイスとして Rokid Glasses を選択する

1. Android Studio 上部のバーにあるデバイスのドロップダウンをクリックする
2. 一覧に **Rokid Glasses** が表示されることを確認する
3. **Rokid Glasses** を選択する

一覧に表示されない場合:

```
adb devices          # Glasses が接続されていることを確認
adb kill-server
adb start-server
adb devices          # もう一度確認

```

### ステップ4 — ビルドしてインストールする

1. Android Studio の緑色の ▶ **Run** ボタンをクリックする
2. Android Studio が以下の処理を行う:
   - Kotlin アプリをコンパイルする
   - APK を Glasses にインストールする
   - アプリを自動的に起動する
3. 初回のビルドには3〜5分かかります。2回目以降は短くなります

### ステップ5 — アプリが実行されていることを確認する

- Glasses を装着する
- Audit C の暗色インターフェースが表示されることを確認する
- ヘッダーに「AUDIT C | LAB QC」と表示される

アプリがクラッシュする場合:

```
# リアルタイムでログを確認
adb logcat | grep -i "auditc\|error\|fatal"

```

クラッシュログを SOLO Coder に貼り付けて修正してください。

***

## パート4 — Glasses の画面を Mac にミラーリングする（デモ発表用）

これは、Glasses 内で起きていることをノートパソコンの画面で審査員に見せるための手順です。

### 方法1 — scrcpy（推奨、最も簡単）

```
# Glasses が開発用ケーブルで接続されていることを確認
adb devices   # デバイスが一覧に表示されることを確認

# Glasses の画面を Mac にミラーリング
scrcpy

# 任意 — タイトルを設定し、操作を無効化
scrcpy --window-title "Audit C — Rokid Glasses View" --no-control

# 任意 — デモを同時に録画
scrcpy --record demo.mp4

```

Mac 上にウィンドウが開き、Glasses に表示されている内容がリアルタイムでそのまま映ります。

### 方法2 — Android Studio の画面ミラーリング

1. Android Studio → View → Tool Windows → Running Devices の順に進む
2. Glasses デバイスを選択する
3. IDE のパネルに画面が表示される

### 発表時

- Mac で scrcpy ウィンドウを開く
- HDMI で Mac をプロジェクターまたはスクリーンに接続する
- 審査員は大画面で Glasses の AR 表示を確認できる
- 発表者が Glasses を装着して操作すると、審査員はミラーリング画面でその様子を確認できる

**ヒント:** scrcpy のウィンドウを大きくし、可能であれば全画面表示にしてください。480×640 の画面が見やすく表示されます。

***

## パート5 — 当日のデモの流れ

### セットアップ（デモ開始15分前に実施）

```
# ターミナル1 — バックエンドを起動
cd /Users/bellz_um/Desktop/Unbound
python3 -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload

# ターミナル2 — Glasses を接続してミラーリングを開始
adb devices
scrcpy --window-title "Audit C — Rokid Glasses View"

```

### デモ中

1. プロジェクターに scrcpy ウィンドウ（Glasses の画面）を表示する
2. Glasses を装着して話すと、審査員は大画面で表示を確認できる
3. **「step one done」** と発話する → Glasses に緑色で VERIFIED ✓ と表示される
4. **「contamination detected」** と発話する → Glasses に赤色で WARNING ⚠ と表示される
5. **「generate report」** と発話する（または長押しボタンを押す）→ レポートが生成される
6. 最後のスライドとして、ノートパソコンで PDF レポートを表示する

***

## パート6 — トラブルシューティング早見表

問題

解決方法

`adb devices` を実行しても何も表示されない

充電用ケーブルではなく、開発用ケーブルを使用しているか確認する

ADB が見つからない

`brew install android-platform-tools`

Gradle の同期に失敗する

エラーを SOLO Coder に貼り付ける

アプリはインストールされるがクラッシュする

`adb logcat` → エラーを SOLO Coder に貼り付ける

scrcpy が見つからない

`brew install scrcpy`

scrcpy の画面が真っ黒になる

先に Glasses の画面ロックを解除する

Glasses からバックエンドに接続できない

同じ Wi-Fi に接続されていることを確認し、`adb shell ping 192.168.1.11` を実行する

音声が認識されない

短い英語のコマンドをはっきりと発話する

TTS の音声が流れない

Glasses の音量を確認する。右テンプルをスワイプして調整する

***

## パート7 — 主要コマンド早見表

```
# Glasses が接続されていることを確認
adb devices

# APK を手動でインストール（Android Studio が動作しない場合）
adb install app/build/outputs/apk/debug/app-debug.apk

# アプリを手動で起動
adb shell am start -n com.auditc.glasses/.MainActivity

# リアルタイムログを表示
adb logcat | grep AuditC

# 画面をミラーリング
scrcpy

# ミラーリングしながら録画
scrcpy --record demo.mp4

# Glasses 上のアプリを再起動
adb shell am force-stop com.auditc.glasses
adb shell am start -n com.auditc.glasses/.MainActivity

# Glasses の Wi-Fi IP を確認（Mac と同じネットワークであることを確認するため）
adb shell ip addr show wlan0

```

***

## パート8 — Glasses が間に合わない場合

代替デモとして、以下の両方を審査員に見せます:

1. **iPhone の Web デモ**（動作確認済み）— Safari を使用してノートパソコンの画面に表示する
2. Android Studio のエミュレーターで動作する **Kotlin アプリ** — AVD（Android Virtual Device）で実行する
   - Android Studio → Device Manager → Create Virtual Device の順に進む
   - Phone → Pixel 4 → API 29 の順に選択する
   - エミュレーターでアプリを実行する。Glasses と同じ UI が表示される

審査員には次のように説明します:

> 「Glasses 用アプリの全機能を実装し、テスト済みです。こちらはエミュレーター版です。ハードウェアが利用可能になれば、同一の APK を ADB 経由で Rokid Glasses にデプロイできます。」

審査員はハードウェアの遅延を理解しています。物理的な Glasses よりもコードの品質が重要です。

***

*Audit C チーム向けガイド | TRAE SOLO Hackathon Tokyo 2026*
