import java.io.File;
2 import java.util.stream.Collectors;
3 import java.util.stream.IntStream;
4 import javax.sound.sampled.AudioSystem;
5 import javax.sound.sampled.AudioFormat;
6 import javax.sound.sampled.AudioInputStream;
7
8 import javafx.application.Application;
9 import javafx.application.Platform;
10 import javafx.stage.Stage;
11 import javafx.scene.Scene;
12 import javafx.scene.chart.XYChart;
13 import javafx.scene.chart.LineChart;
14 import javafx.scene.chart.NumberAxis;
15 import javafx.collections.ObservableList;
16 import javafx.collections.FXCollections;
17
18 import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
19
20 import java.io.IOException;
21 import javax.sound.sampled.UnsupportedAudioFileException;
22
23 public final class PlotWaveformSimple extends Application {
24
25 @Override public final void start(final Stage primaryStage)
26 throws IOException,
27 UnsupportedAudioFileException {
28 /* コマンドライン引数処理*/
29 final String[] args = getParameters().getRaw().toArray(new String[0]);
30 if (args.length < 1) {
31 System.out.println("WAVFILE is not given.");
32 Platform.exit();
33 return;
34 }
35 final File wavFile = new File(args[0]);
36
37 /* W A V ファイル読み込み*/
38 final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
39 final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
40 final AudioFormat format = stream.getFormat();
41 final double sampleRate = format.getSampleRate();
42 stream.close();
43
44 /* データ系列を作成*/
45 final ObservableList<XYChart.Data<Number, Number>> data =
46 IntStream.range(0, waveform.length)
47 .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate, waveform[i]))
48 .collect(Collectors.toCollection(FXCollections::observableArrayList));
49
50 /* データ系列に名前をつける*/
51 final XYChart.Series<Number, Number> series = new XYChart.Series<>();
52 series.setName("Waveform");
53 series.setData(data);
/* 軸を作成*/
56 final NumberAxis xAxis = new NumberAxis();
57 xAxis.setLabel("Time (seconds)");
58 final NumberAxis yAxis = new NumberAxis();
59 yAxis.setLabel("Amplitude");
60
61 /* チャートを作成*/
62 final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
63 chart.setTitle("Waveform");
64 chart.setCreateSymbols(false);
65 chart.getData().add(series);
66
67 /* グラフ描画*/
68 final Scene scene = new Scene(chart, 800, 600);
69
70 /* ウインドウ表示*/
71 primaryStage.setScene(scene);
72 primaryStage.setTitle(getClass().getName());
73 primaryStage.show();
74 }
75
76 }