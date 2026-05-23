# Orgon support for Unstoppable Wallet (Fork B)

This repository contains all code needed to add Orgon blockchain support
to a fork of Unstoppable Wallet Android.

## Repository structure

```
orgon-kit-android/           ← Standalone Kotlin library (publish to JitPack)
  orgonkit/
    OrgonKit.kt              ← Main entry point
    models/
      Address.kt             ← Base58Check with 0x73 prefix
      Signer.kt              ← BIP44 m/44'/195'/0'/0/0 derivation + ECDSA signing
      RpcSource.kt           ← Node URL config (tr80/tr81.orgon.space)
      Models.kt              ← FullTransaction, SyncState, Token, etc.
    network/
      OrgonJsonRpcProvider.kt ← HTTP JSON-RPC client
    storage/                  ← Room database (mirror tron-kit-android)
    transactions/             ← Transaction sync manager

unstoppable-orgon/           ← Patch files for unstoppable-wallet-android fork
  adapters/
    OrgonAdapter.kt           ← Wallet core adapter
    OrgonTransactionRecord.kt ← TX display model
    ISendOrgonAdapter.kt      ← Send interface
  blockchain/
    BlockchainType_Orgon_diff.kt ← Changes to BlockchainType.kt (with comments)
    coins_orgon.json          ← Entry to add to coins.json
  managers/
    OrgonBlockchainSettingsFragment.kt ← Settings UI (custom RPC)
    OrgonBlockchainSettingsViewModel.kt
```

---

## Step 1 — Publish orgon-kit-android to JitPack

1. Create a new GitHub repository: `your-org/orgon-kit-android`
2. Push all files from `orgon-kit-android/` to it
3. Create a GitHub Release with tag `v1.0.0`
4. Visit https://jitpack.io/#your-org/orgon-kit-android — JitPack auto-builds

Dependency string:
```groovy
implementation 'com.github.your-org:orgon-kit-android:v1.0.0'
```

---

## Step 2 — Fork unstoppable-wallet-android

```bash
git clone https://github.com/horizontalsystems/unstoppable-wallet-android
cd unstoppable-wallet-android
git checkout -b feature/orgon-support
```

---

## Step 3 — Add dependency

In `app/build.gradle`, add alongside tron-kit-android:
```groovy
implementation 'com.github.your-org:orgon-kit-android:v1.0.0'
```

---

## Step 4 — Apply code changes

### 4a. BlockchainType.kt
Follow the diff comments in `BlockchainType_Orgon_diff.kt`.
The key additions (search for `Tron ->` and add `Orgon ->` alongside):

```kotlin
object Orgon : BlockchainType()

// In uid property:
is Orgon -> "orgon"

// In tokenTypes:
is Orgon -> listOf(TokenType.Native, TokenType.Eip20)

// In order:
is Orgon -> 14
```

### 4b. AdapterFactory.kt
Add alongside the Tron adapter creation:
```kotlin
BlockchainType.Orgon -> {
    val words = accountWords(wallet.account)
    val rpcSource = orgonRpcSource(wallet)
    OrgonAdapter.getInstance(context, wallet, words, rpcSource)
}
```

### 4c. Copy adapter files
Copy from `unstoppable-orgon/adapters/` into:
```
app/src/main/java/io/horizontalsystems/bankwallet/core/adapters/OrgonAdapter.kt
app/src/main/java/io/horizontalsystems/bankwallet/core/adapters/ISendOrgonAdapter.kt
app/src/main/java/io/horizontalsystems/bankwallet/entities/transactionrecords/orgon/OrgonTransactionRecord.kt
```

### 4d. Copy settings UI
Copy from `unstoppable-orgon/managers/` into:
```
app/src/main/java/io/horizontalsystems/bankwallet/modules/settings/blockchain/orgon/
```
Register fragment in `nav_blockchain_settings.xml`.

### 4e. Add coins.json entry
Merge `coins_orgon.json` into:
```
app/src/main/assets/coins.json
```

### 4f. Add drawable icon
Add `orgon.png` (or `.svg`) to `app/src/main/res/drawable/`.
Recommended: use Orgon logo from dev.orgon.space branding assets.

---

## Step 5 — Branding (optional)

Change app name and icon in `app/src/main/res/` and `app/build.gradle`:
```groovy
applicationId "io.horizontalsystems.orgonwallet"   // or keep original with Orgon suffix
```

Update `app_name` in `strings.xml`.

---

## Key Orgon parameters (reference)

| Parameter | Value |
|---|---|
| Address prefix byte | `0x73` |
| Address starts with | `o` |
| BIP44 coin_type | `195` |
| Derivation path | `m/44'/195'/0'/0/0` |
| Fullnode URL | `https://tr80.orgon.space` |
| Solidity URL | `https://tr81.orgon.space` |
| Fullnode gRPC port | `19067` |
| Solidity gRPC port | `19068` |
| Decimals | `6` (1 ORGON = 1,000,000 SUN) |
| Token standards | `oRC-10`, `oRC-20` |
| signMessageV2 prefix | `\x19TRON Signed Message:\n` (identical to Tron) |
| Block finality | ~19 blocks |
| Developer docs | https://dev.orgon.space |

---

## Testing checklist

- [ ] Generate address from seed phrase — confirm starts with `o`
- [ ] Fetch balance from `tr80.orgon.space`
- [ ] Display address in receive screen
- [ ] Send ORGON — verify tx on explorer
- [ ] Import existing Tron mnemonic — confirm same address on Orgon
- [ ] Custom RPC node — settings persist after app restart
- [ ] oRC-20 token balance loads correctly
- [ ] Transaction history displays
