"""把 dist/FNTV 打成安装程序。安装时可选路径，并可在系统“应用”里卸载。"""

import json
import os
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent
INNO_URL = "https://jrsoftware.org/download.php/is.exe"
ISL_URLS = [
    "https://raw.githubusercontent.com/jrsoftware/issrc/main/Files/Languages/ChineseSimplified.isl",
    "https://cdn.jsdelivr.net/gh/jrsoftware/issrc@main/Files/Languages/ChineseSimplified.isl",
]
ISS = r"""
#define AppVersion "{version}"
[Setup]
AppId={{{{8F3E2C1A-6B4D-4E91-9C27-5D0A7F1E4B83}}}}
AppName=飞牛TV
AppVersion={{#AppVersion}}
AppPublisher=飞牛TV
DefaultDirName={{autopf}}\飞牛TV
DefaultGroupName=飞牛TV
DisableProgramGroupPage=yes
OutputDir=.
OutputBaseFilename=FNTV_windows_v{{#AppVersion}}
SetupIconFile=..\fntv.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64
ArchitecturesInstallIn64BitMode=x64
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
UsePreviousAppDir=yes
CloseApplications=yes
RestartApplications=no
UninstallDisplayName=飞牛TV
UninstallDisplayIcon={{app}}\FNTV.exe

[Languages]
Name: "chinesesimp"; MessagesFile: "ChineseSimplified.isl"

[Files]
Source: "FNTV\\*"; DestDir: "{{app}}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{{autodesktop}}\飞牛TV"; Filename: "{{app}}\FNTV.exe"

[Run]
Filename: "{{app}}\FNTV.exe"; Description: "启动飞牛TV"; Flags: nowait postinstall skipifsilent

[Code]
var
  DeleteAccountCheck: TNewCheckBox;

procedure InitializeUninstallProgressForm();
begin
  DeleteAccountCheck := TNewCheckBox.Create(UninstallProgressForm);
  DeleteAccountCheck.Parent := UninstallProgressForm;
  DeleteAccountCheck.Caption := '同时删除已保存的账号信息';
  DeleteAccountCheck.Checked := False;
  DeleteAccountCheck.Left := ScaleX(48);
  DeleteAccountCheck.Top := UninstallProgressForm.CancelButton.Top - ScaleY(28);
  DeleteAccountCheck.Width := UninstallProgressForm.CancelButton.Left - ScaleX(64);
  DeleteAccountCheck.Height := ScaleY(17);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    DeleteAccountCheck.Enabled := False;
  if (CurUninstallStep = usPostUninstall) and DeleteAccountCheck.Checked then
    DelTree(ExpandConstant('{{userappdata}}\FNTVDesktop'), True, True, True);
end;
"""


def main():
    source = ROOT / "dist" / "FNTV" / "FNTV.exe"
    if not source.is_file():
        raise SystemExit("还没有 dist\\FNTV\\FNTV.exe，请先完成打包")
    version = json.loads((ROOT / "version.json").read_text(encoding="utf-8"))["versionName"].strip()
    if not version:
        raise SystemExit("desktop/version.json 里没有 versionName")
    script = ROOT / "dist" / "installer.iss"
    ensure_language(script.parent / "ChineseSimplified.isl")
    script.write_text(ISS.format(version=version), encoding="utf-8-sig")
    subprocess.run([iscc(), str(script)], cwd=ROOT, check=True)
    out = ROOT / "dist" / ("FNTV_windows_v%s.exe" % version)
    if not out.is_file():
        raise SystemExit("没有生成安装程序")
    info = ROOT / "dist" / "release-info.txt"
    info.write_text(
        "version=%s\ntag=desktop-%s\nfile=desktop/dist/%s\n" % (version, version, out.name),
        encoding="utf-8",
    )
    changelog = ""
    update_path = ROOT / "update.json"
    if update_path.is_file():
        changelog = json.loads(update_path.read_text(encoding="utf-8")).get("changelog") or ""
    (ROOT / "dist" / "changelog.txt").write_text(changelog, encoding="utf-8")
    print("已生成 %s" % out)


def ensure_language(dest):
    if dest.is_file() and dest.stat().st_size > 1000:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    last = "未知错误"
    for url in ISL_URLS:
        try:
            print("下载简体中文语言包")
            request = urllib.request.Request(url, headers={"User-Agent": "FNTV"})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
            if len(data) > 1000 and b"[LangOptions]" in data:
                dest.write_bytes(data)
                return
            last = "内容不完整"
        except Exception as exc:
            last = str(exc)
    raise SystemExit("下载简体中文语言包失败: %s" % last)


def iscc():
    names = []
    if os.environ.get("INNO_SETUP"):
        names.append(os.environ["INNO_SETUP"])
    program_files = [
        os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
        os.environ.get("ProgramFiles", r"C:\Program Files"),
    ]
    for root in program_files:
        names.append(str(Path(root) / "Inno Setup 6" / "ISCC.exe"))
    for name in names:
        if name and Path(name).is_file():
            return name
    return install_inno()


def install_inno():
    setup = Path(tempfile.gettempdir()) / "innosetup.exe"
    if not setup.is_file() or setup.stat().st_size < 100000:
        print("下载 Inno Setup")
        urllib.request.urlretrieve(INNO_URL, setup)
    print("安装 Inno Setup")
    subprocess.run(
        [str(setup), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-"],
        check=True,
    )
    compiler = iscc_installed()
    if not compiler:
        raise SystemExit("没有找到 ISCC.exe")
    return compiler


def iscc_installed():
    for root in (
        os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"),
        os.environ.get("ProgramFiles", r"C:\Program Files"),
    ):
        compiler = Path(root) / "Inno Setup 6" / "ISCC.exe"
        if compiler.is_file():
            return str(compiler)
    return ""


if __name__ == "__main__":
    main()
