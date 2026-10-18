#!/data/data/com.termux/files/usr/bin/sh
# Run this inside the phoneai folder in Termux. It puts the project on your GitHub
# so GitHub can build the APK for you.
set -e
pkg install -y git gh
[ -d .git ] || git init -b main
git config user.name  >/dev/null 2>&1 || git config user.name  "PhoneAI"
git config user.email >/dev/null 2>&1 || git config user.email "phoneai@users.noreply.github.com"
gh auth status >/dev/null 2>&1 || gh auth login -p https -w
git add -A
git commit -m "Phone AI" >/dev/null 2>&1 || true
if gh repo view phoneai >/dev/null 2>&1; then
  git remote get-url origin >/dev/null 2>&1 || git remote add origin "$(gh repo view phoneai --json url -q .url)"
  git push -u origin main
else
  gh repo create phoneai --public --source=. --remote=origin --push
fi
echo ""
echo "DONE. Now open GitHub > your phoneai repo > Actions tab and wait for the green tick (3-6 minutes)."
echo "Then open the repo's Releases page and download app-debug.apk."
