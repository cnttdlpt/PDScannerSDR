# PD Scanner SDR + GIS cho Android — v0.5

Bản này tích hợp ba phần trong cùng một app:

1. **RTL-SDR realtime** để sàng lọc tín hiệu RF dạng xung nghi phóng điện.
2. **GPS Android** để lấy vị trí người kiểm tra.
3. **GIS cột điện offline** để tự xác định cột gần nhất và gắn kết quả PD vào đúng tài sản.

> Đây là thiết bị **screening / khoanh vùng**, không phải máy đo PD chuẩn và không đo pC. Khi app báo nghi ngờ/đỏ, cần dùng máy PD chuyên dụng để xác nhận.

## Dữ liệu GIS đã đóng gói

Nguồn dùng để tạo asset trong app:

- `npc_tt_cotdien_2.dbf`
- `npc_tt_cotdien_2.prj`
- Số bản ghi: **7.859 cột**
- Hệ tọa độ theo `.prj`: **GCS_WGS_1984 / WGS84**
- `xDaiDien` = longitude
- `yDaiDien` = latitude

Các trường được giữ lại trong app:

`maDoiTuong`, `soHieu`, `capDienAp`, `donViQuanL`, `diaChi`, `loCapDien`, `xDaiDien`, `yDaiDien`, `tenDoiTuon`, `ghiChu`, `taiSan`.

Dữ liệu DBF 43 MB đã được rút gọn thành asset `poles.tsv` khoảng 1,3 MB để app tải nhanh và chạy hoàn toàn offline.

## Cách app nhận cột

GPS điện thoại và GIS đều ở WGS84 nên app so trực tiếp tọa độ rồi tìm điểm gần nhất.

- **≤ 20 m**: `ĐÃ KHỚP CỘT`
- **20–40 m**: `CỘT GẦN NHẤT - KIỂM TRA LẠI`
- **> 40 m**: chưa tự gắn kết quả vào cột để tránh nhầm tài sản

Khoảng cách được tính lại theo Haversine sau khi chọn điểm gần nhất.

## Phần cứng

- RTL-SDR Blog V3
- Nagoya-773
- Android USB-C
- Hub USB-C có PD cấp nguồn ngoài
- Power bank
- LNA chỉ dùng khi thật sự cần; nếu cấp 5 V riêng cho LNA thì không bật Bias-T cùng lúc

## Driver RTL-SDR

App v0.5 vẫn dùng Android **SDR driver** package:

`marto.rtl_tcp_andro`

Driver mở thiết bị USB và cung cấp luồng I/Q qua `rtl_tcp` nội bộ ở `127.0.0.1:1234`.

## Cách dùng ngoài hiện trường

1. Cắm RTL-SDR qua hub có nguồn ngoài.
2. Mở app và cấp quyền vị trí.
3. App nạp 7.859 cột GIS offline và hiển thị cột gần nhất.
4. Bấm **KẾT NỐI RTL-SDR** và cho SDR Driver quyền truy cập USB.
5. Khuyến nghị bấm **LẤY NỀN** tại khu vực tương đối sạch trước.
6. Bật **REALTIME LIÊN TỤC** rồi di chuyển tới từng cột.
7. Khi trong vòng 40 m, app gắn kết quả hiện tại vào cột GIS.
8. Bấm **LƯU KẾT QUẢ CHO CỘT NÀY** để ghi CSV.

## File kết quả

App ghi file:

`pd_scan_results.csv`

trong thư mục app-specific external storage của Android. Mỗi dòng gồm:

- thời gian
- mã đối tượng
- số hiệu cột
- cấp điện áp
- đơn vị quản lý
- lộ/tuyến
- tọa độ GIS
- tọa độ GPS thực tế
- sai số GPS
- khoảng cách tới cột
- PD Score
- số dải nghi ngờ
- dải mạnh nhất
- pulse rate / crest / P99 / kurtosis
- địa chỉ

## REALTIME

RTL-SDR chỉ tune được một center-frequency tại một thời điểm. Vì vậy app quét luân phiên các tần số đã nhập.

Mặc định:

`150, 300, 450, 600, 750 MHz`

Sau khi thử thực tế, nếu xác định được 1–3 dải hiệu quả nhất thì nên chỉ giữ các dải đó để tăng tốc phản hồi realtime.

## Màu cảnh báo

- **0–34 Xanh**: chưa thấy dấu hiệu rõ
- **35–64 Vàng**: nghi ngờ
- **65–100 Đỏ**: có tín hiệu RF dạng xung đáng nghi, cần kiểm tra bằng máy PD chuyên dụng

## Build trên Windows

1. Cài Android Studio.
2. Mở thư mục `PDScannerSDR_Android`.
3. Chờ Gradle Sync.
4. Cài Android SDK Platform 35 nếu Android Studio yêu cầu.
5. Chọn **Build > Build APK(s)**.
6. APK debug thường ở:

`app/build/outputs/apk/debug/app-debug.apk`

Yêu cầu Java 17, minSdk 26, targetSdk 35.

## An toàn

Bộ SDR chỉ dùng thu thụ động. Không đưa điện thoại, anten, cáp hoặc SDR vào vùng tiếp cận nguy hiểm của thiết bị đang mang điện. Tuân thủ khoảng cách và quy trình an toàn điện của đơn vị.


## Giao diện v0.5

Bản v0.5 đổi giao diện theo phong cách dashboard hiện trường:

- Header tối màu, trạng thái GIS/GPS dạng chip.
- Card cột gần nhất nổi bật và dễ đọc ngoài hiện trường.
- PD Score lớn, có thanh tiến trình và màu Xanh/Vàng/Đỏ dịu hơn.
- Nút REALTIME là thao tác chính, các chức năng khác gom trong card RTL-SDR.
- Nút lưu kết quả nổi bật.
- Tối ưu khoảng cách, bo góc, viền và độ tương phản để dùng trên điện thoại.


## Giao diện v0.5 — Google / Material You style

- Bo góc lớn 26–32dp cho card.
- Nút dạng pill bo tròn tối đa.
- Màu nền sáng, surface trắng và primary blue kiểu Google.
- Header dùng primary-container thay vì khối màu đậm.
- Chip GIS/GPS dạng pill.
- PD Score dùng các container Xanh/Vàng/Đỏ kiểu Material You.
- Nút realtime là primary action; các nút còn lại dùng tonal/ghost style.
