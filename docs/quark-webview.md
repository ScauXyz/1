# Official website workflow

The current Android entry point is a native Compose / Material 3 home with two primary actions: 上传文件 (upload files) and 查看文件 (view files). These open the owner-bound official Quark folder. The existing mock backend, repository, Room/WorkManager implementation and authorization tests are retained; the new home does not register a mock device, start backup work or present mock uploads as Quark uploads. Previously scheduled legacy work, if upgrading an existing installation, still targets only the mock backend.

## Owner setup

1. Set a secure Android screen lock. Tap 所有者设置 and complete the system device-credential prompt. Anyone knowing this device's unlock credential is considered an owner; this is not a separate administrator identity.
2. Open the official website. Manually complete login, QR/SMS prompts and any account challenges in the WebView. No app password field, native JavaScript bridge, cookie reader/export, private API or account-token handling is implemented. WebView manages its own first-party website session and local storage, including normal session persistence. Destroying the view does not explicitly erase its website data. Clearing app data removes the local setup/session.
3. Navigate to a concrete folder. Tap 使用当前文件夹 to return its URL to the native settings form, or enter the complete official folder URL. Review and save. Saving does not verify that the server recognizes or permits that folder.
4. Binding accepts only HTTPS `pan.quark.cn`, without credentials, port or query, with `/` or `/list` and a fragment `/list/all/<folder-id>`. Root/global routes, including folder ID `0`, are rejected. This is a deliberately narrow, provisional website-route contract, not an official API guarantee. If Quark changes its routing, fail closed and update the policy after manual verification.

Owner access lasts at most five minutes and is discarded when the app leaves the foreground or the owner saves/locks settings. The credential prompt itself returning successfully creates a new owner session. Owner state is memory-only and is lost on process recreation. Without secure lock or successful authentication, settings remain unavailable. Owner browsing remains restricted to the explicit official origin; off-origin identity redirects, popups and permissions are blocked. If the official login requires such a redirect it will fail; verify the actual required origin before changing any allowlist.

## Upload and viewing

Upload uses Android OpenMultipleDocuments. Only returned `content:` URIs are kept in memory. Names are displayed for native confirmation. Cancellation delivers no files. The selected files are not sent to the mock backend or read for an automatic upload. No persistent URI grant or original-file deletion is performed in this workflow.

The bound page must itself invoke `WebChromeClient.onShowFileChooser`. Only a confirmed selection, in the bound page, in upload mode, matching the website's MIME/count request, can be delivered. Capture/save requests and chooser calls during owner login/view mode are canceled. Unreadable or revoked grants fail with a native message. Each WebView can hand off the selection once. Leaving for home or process recreation discards it. There is no scripted upload click, DOM automation, intercepted credential, private endpoint, synthetic completion or inferred success from page load.

After handoff, the app reports only that files were delivered to the page. The owner/user must inspect the website's actual upload status, destination, content and any errors. Offline, SSL, HTTP, blocked navigation, expired authentication and renderer errors have native messages. Reload retries the current allowed page. External downloads, new windows and website camera/microphone permissions are blocked. Back/URL history is cleared within the WebView; 首页 exits to the native home.

## Security boundary

**WebView restriction is soft isolation, not server authorization.** The website session can have the account's full authority. Main-frame requests, navigation callbacks, SPA history updates and native chooser entry share `QuarkPolicy`. Network document checks account for HTTP omitting URL fragments while UI navigation still requires the bound fragment; HTTPS and SSL checks remain enabled, file access is disabled and no native JS bridge exists. Official JavaScript and subresource requests are not converted into a folder ACL. Same-document UI, in-flight requests, cached content, renderer behavior and changes made by the website may expose functionality beyond what navigation callbacks can prevent. History detection can occur after page state changes. View mode is not a read-only server permission; page controls may still modify account content. Do not entrust private full-account content to this UI boundary. Use an account containing only content acceptable for the device, or an official server-authorized integration for strong isolation.

The retained FastAPI backend is a separate trust domain. Its device-token ownership/allowlist checks remain authoritative for every backend resource access. A bound Quark URL does not grant a backend permission; a backend device token does not scope the Quark WebView account. `QuarkStorageProvider` remains disabled pending an authorized API. The legacy statement that Android never contains a cloud-account session applies to the mock native HTTP path, not to the newly requested WebView-managed website session. The app never extracts that session into native code.

## Device acceptance checklist (not yet executed)

- On small screens, large font and landscape: check scrolling, full-width primary actions, settings field, long-name confirmation dialog, native messages and WebView height.
- With no screen lock, canceled authentication, expiry, backgrounding, process death and device rotation: verify settings fail closed and selections are not reused.
- Manually log in with a real owner account and prove session persistence across restart without app credential handling; verify official origin/route compatibility.
- Verify concrete folder capture/save, missing/deleted folder, login expiry, parent/global/other-folder links, SPA route changes, redirects, popups and external schemes.
- Choose one/multiple files, cancel, revoke provider access, change MIME/count requirements and trigger the website chooser twice. Verify exact file selection and single handoff.
- Observe real website upload progress/failure; verify resulting bytes/destination independently. Do not count file handoff as upload success.
- Test offline, certificate failure, HTTP errors and renderer termination. Real account/SMS/device interactions belong to the owner and do not block automated work.
