# Android 记录与回查

在 0.1.0 中，从首页记下的原话和从其他应用分享的文字都存进手机上的同一份资料库。保存成功后，叠石显示记录编号并打开详情。以后从时间线、记录本或搜索找到这条记录，打开的都是同一个 `recordId`。

## 从首页或系统分享保存

| 入口 | 操作 | 保存的来源 |
| --- | --- | --- |
| 首页 · 记录 | 写下原话。「添加材料」把补充文字放入同一份草稿。语音入口调用系统识别，设备缺少识别服务时可手动转写。 | `app_home_record` |
| 首页 · 对话 | 切换到「对话」后继续编辑同一份草稿。开启自动整理时，可以在这里提交晚餐提问，到详情查看处理结果。 | `app_home_dialog` |
| 系统分享 | 从其他应用分享纯文本，核对弹窗中的原话后点「确认保存」。取消后可以重新发起分享。 | `android_share` |

两个入口都调用 `recordExperience`。同一次保存失败后重试沿用原操作编号。自动整理开关决定新记录是否创建整理任务，保存回执在本地事务提交后就能返回。[首页](../android/app/src/main/java/com/pebblepile/android/HomeScreen.kt) · [分享与导航](../android/app/src/main/java/com/pebblepile/android/MainActivity.kt) · [接口与回执](core-api-contract.md)

## 在记录库找回原话

时间线按采集日期分组，记录本把同一批记录列成条目。搜索可匹配标题、原话、可见标签和人物。标签支持多选，多项同时命中才会显示。再选人物或事项状态，可以进一步缩小范围。从详情返回后，页面保留视图和仍有效的筛选条件。[记录库](../android/app/src/main/java/com/pebblepile/android/LibraryScreen.kt)

详情按记录编号加载原话、来源版本、标签与事项。点标签可以查看依据和算法版本，确认、修改、排除或标为待核对。排除后的标签退出记录库筛选。编辑「当前事项」可以更新名称和进展。整理任务按自身状态提供暂停、继续、取消或重试。[记录详情](../android/app/src/main/java/com/pebblepile/android/RecordDetailScreen.kt)

## 晚餐建议、反馈与纠正

1. 在详情等晚餐排序完成。首选和其余候选按发布顺序显示。打开某道菜的依据，可以核对来自哪次经历、哪一版原话和哪段引文。
2. 点候选可把菜名带入「实际选择」。最后吃了别的菜可选「其他菜品」并填写体验，反馈仍关联当时看到的建议。单独记录一次体验可点「改为独立体验」。
3. 修改适用条件时，「仅本次」写入当前事项，「以后按此条件」更新晚餐的长期条件。遇到他处已保存的新修订，详情保留草稿，点「查看最新版本」核对后再提交。
4. 从已保存的反馈点「查看当时的候选与依据」，可以对照原建议和实际吃的菜。纠正条件后，也可从「选择此前的建议」为早先的建议补记反馈。依据被删除时，反馈保留建议编号，详情提示原建议已撤回。[晚餐排序](algorithm/dinner-ranking.md)

## 设备复验

在测试设备上安装调试包，开启飞行模式。先在首页保存一条原话，再从系统分享保存另一条，分别记下回执中的编号。也可从终端发起纯文本分享。

```powershell
adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT Shared_record -n com.pebblepile.android/.MainActivity
```

确认两条记录都可从记录库打开。强制结束应用后冷启动，再按原来的编号、原话和来源核对一遍。

```powershell
adb shell am force-stop com.pebblepile.android
adb shell am start -n com.pebblepile.android/.MainActivity
```

页面恢复可分别检查两处草稿。在分享确认前重建 Activity，原话应仍可确认保存。编辑纠正条件时重建 Activity，草稿和适用范围应保留。晚餐历史回查使用固定样例，分别验证「先反馈再纠正」和「先纠正再为此前的建议补记反馈」。[记录与分享用例](../android/app/src/androidTest/java/com/pebblepile/android/RecordFlowTest.kt) · [晚餐详情集成用例](../android/app/src/androidTest/java/com/pebblepile/android/DinnerDetailIntegrationTest.kt) · [验收步骤和历次结果](testing/README.md)
