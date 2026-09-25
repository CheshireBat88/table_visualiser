package com.groupfund.app.data.sheets

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Клиент Google Sheets API v4 на базе Retrofit.
 * Токен доступа передаётся в каждом запросе через @Header.
 */
interface SheetsApiService {

    @POST("v4/spreadsheets")
    suspend fun createSpreadsheet(
        @Header("Authorization") authorization: String,
        @Body body: CreateSpreadsheetRequest,
    ): SpreadsheetResponse

    @POST("v4/spreadsheets/{spreadsheetId}/values:batchUpdate")
    suspend fun batchUpdateValues(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Body body: BatchUpdateValuesRequest,
    ): BatchUpdateValuesResponse

    /** Очищает значения в нескольких диапазонах одним запросом (формат не трогает). */
    @POST("v4/spreadsheets/{spreadsheetId}/values:batchClear")
    suspend fun batchClearValues(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Body body: BatchClearValuesRequest,
    ): BatchClearValuesResponse

    @GET("v4/spreadsheets/{spreadsheetId}/values/{range}")
    suspend fun getValues(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Path("range") range: String,
        @Query("majorDimension") majorDimension: String = "ROWS",
    ): ValuesResponse

    /** Читает несколько диапазонов одним запросом (вместо N отдельных GET — меньше расход квоты). */
    @POST("v4/spreadsheets/{spreadsheetId}/values:batchGet")
    suspend fun batchGetValues(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Body body: BatchGetValuesRequest,
    ): BatchGetValuesResponse

    /** Метаданные таблицы (список листов, название, ссылка). */
    @GET("v4/spreadsheets/{spreadsheetId}")
    suspend fun getSpreadsheetMeta(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Query("fields") fields: String = "spreadsheetId,properties.title,spreadsheetUrl,sheets.properties.title",
    ): SpreadsheetMeta

    /** Структурные изменения (например, добавить лист). */
    @POST("v4/spreadsheets/{spreadsheetId}:batchUpdate")
    suspend fun batchUpdateSpreadsheet(
        @Header("Authorization") authorization: String,
        @Path("spreadsheetId") spreadsheetId: String,
        @Body body: SpreadsheetBatchUpdateRequest,
    ): retrofit2.Response<Any>
}

/** Drive API v3: управление файлами и правами. */
interface DriveApiService {

    @POST("drive/v3/files/{fileId}/permissions")
    suspend fun addPermission(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("sendNotificationEmail") sendNotificationEmail: Boolean = false,
        @Body body: PermissionRequest,
    ): PermissionResponse

    /** Удаляет файл навсегда (не в корзину). */
    @DELETE("drive/v3/files/{fileId}")
    suspend fun deleteFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
    )

    /** Метаданные одного файла (имя, ссылка на просмотр и т.п.). */
    @GET("drive/v3/files/{fileId}")
    suspend fun getFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Query("fields") fields: String,
    ): DriveFile

    /** Список файлов с фильтрами (поиск групп для импорта). */
    @GET("drive/v3/files")
    suspend fun listFiles(
        @Header("Authorization") authorization: String,
        @Query("q") query: String,
        @Query("fields") fields: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
    ): DriveFileList

    /** Патчит файл (проставляет маркер «создано приложением»). */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun patchFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Body body: DriveFilePatch,
    )

    /** Переименовывает файл — новое название таблицы видят все, у кого есть доступ. */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun renameFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String,
        @Body body: DriveFileRename,
    )
}

// ---------- DTO: создание таблицы ----------

data class CreateSpreadsheetRequest(
    @SerializedName("properties") val properties: SpreadsheetPropertiesRequest,
    @SerializedName("sheets") val sheets: List<SheetRequestContent> = emptyList(),
)

data class SpreadsheetPropertiesRequest(
    @SerializedName("title") val title: String,
)

data class SheetRequestContent(
    @SerializedName("properties") val properties: SheetPropertiesRequest,
)

data class SheetPropertiesRequest(
    @SerializedName("title") val title: String,
)

data class SpreadsheetResponse(
    @SerializedName("spreadsheetId") val spreadsheetId: String,
    @SerializedName("spreadsheetUrl") val spreadsheetUrl: String,
)

// ---------- DTO: запись значений ----------

data class BatchUpdateValuesRequest(
    @SerializedName("valueInputOption") val valueInputOption: String = "USER_ENTERED",
    @SerializedName("data") val data: List<ValueRange>,
)

data class ValueRange(
    @SerializedName("range") val range: String,
    @SerializedName("values") val values: List<List<Any?>>,
    @SerializedName("majorDimension") val majorDimension: String = "ROWS",
)

data class BatchUpdateValuesResponse(
    @SerializedName("totalUpdatedCells") val totalUpdatedCells: Int? = null,
)

data class BatchClearValuesRequest(
    @SerializedName("ranges") val ranges: List<String>,
)

data class BatchClearValuesResponse(
    @SerializedName("clearedRanges") val clearedRanges: List<String>? = null,
)

// ---------- DTO: чтение значений ----------

data class ValuesResponse(
    @SerializedName("range") val range: String? = null,
    @SerializedName("values") val values: List<List<Any?>>? = null,
)

data class BatchGetValuesRequest(
    @SerializedName("ranges") val ranges: List<String>,
    @SerializedName("majorDimension") val majorDimension: String = "ROWS",
)

data class BatchGetValuesResponse(
    @SerializedName("valueRanges") val valueRanges: List<BatchGetValueRange>? = null,
)

data class BatchGetValueRange(
    @SerializedName("range") val range: String? = null,
    @SerializedName("values") val values: List<List<Any?>>? = null,
)

// ---------- DTO: права Drive ----------

data class PermissionRequest(
    @SerializedName("role") val role: String = "reader",
    @SerializedName("type") val type: String = "anyone",
)

data class PermissionResponse(
    @SerializedName("id") val id: String? = null,
)

// ---------- DTO: метаданные таблицы ----------

data class SpreadsheetMeta(
    @SerializedName("spreadsheetId") val spreadsheetId: String? = null,
    @SerializedName("properties") val properties: SpreadsheetMetaProperties? = null,
    @SerializedName("spreadsheetUrl") val spreadsheetUrl: String? = null,
    @SerializedName("sheets") val sheets: List<SheetMeta>? = null,
)

data class SpreadsheetMetaProperties(
    @SerializedName("title") val title: String? = null,
)

data class SheetMeta(
    @SerializedName("properties") val properties: SheetPropertiesMeta? = null,
)

data class SheetPropertiesMeta(
    @SerializedName("sheetId") val sheetId: Int? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("index") val index: Int? = null,
    @SerializedName("gridProperties") val gridProperties: GridPropertiesMeta? = null,
)

data class GridPropertiesMeta(
    @SerializedName("hidden") val hidden: Boolean? = null,
)

// ---------- DTO: поиск на Drive ----------

data class DriveFileList(
    @SerializedName("files") val files: List<DriveFile>? = null,
    @SerializedName("nextPageToken") val nextPageToken: String? = null,
)

data class DriveFile(
    @SerializedName("id") val id: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("webViewLink") val webViewLink: String? = null,
    @SerializedName("createdTime") val createdTime: String? = null,
    @SerializedName("appProperties") val appProperties: Map<String, String>? = null,
)

data class DriveFilePatch(
    @SerializedName("appProperties") val appProperties: Map<String, String>,
)

data class DriveFileRename(
    @SerializedName("name") val name: String,
)

// ---------- DTO: структурные изменения и форматирование ----------

data class SpreadsheetBatchUpdateRequest(
    @SerializedName("requests") val requests: List<SpreadsheetBatchUpdateRequest.Request>,
) {
    data class Request(
        @SerializedName("addSheet") val addSheet: AddSheetRequest? = null,
        @SerializedName("repeatCell") val repeatCell: RepeatCellRequest? = null,
        @SerializedName("updateSheetProperties") val updateSheetProperties: UpdateSheetPropertiesRequest? = null,
        @SerializedName("autoResizeDimensions") val autoResizeDimensions: AutoResizeDimensionsRequest? = null,
        @SerializedName("updateDimensionProperties") val updateDimensionProperties: UpdateDimensionPropertiesRequest? = null,
    )
}

data class AddSheetRequest(
    @SerializedName("properties") val properties: AddSheetProperties? = null,
)

data class AddSheetProperties(
    @SerializedName("title") val title: String,
)

data class RepeatCellRequest(
    @SerializedName("range") val range: GridRange,
    @SerializedName("cell") val cell: CellData,
    @SerializedName("fields") val fields: String = "userEnteredFormat",
)

data class GridRange(
    @SerializedName("sheetId") val sheetId: Int,
    @SerializedName("startRowIndex") val startRowIndex: Int,
    @SerializedName("endRowIndex") val endRowIndex: Int,
    @SerializedName("startColumnIndex") val startColumnIndex: Int,
    @SerializedName("endColumnIndex") val endColumnIndex: Int,
)

data class CellData(
    @SerializedName("userEnteredFormat") val userEnteredFormat: CellFormat? = null,
)

data class CellFormat(
    @SerializedName("backgroundColor") val backgroundColor: Rgb? = null,
    @SerializedName("textFormat") val textFormat: TextFormat? = null,
    @SerializedName("horizontalAlignment") val horizontalAlignment: String? = null,
    @SerializedName("verticalAlignment") val verticalAlignment: String? = null,
    @SerializedName("borders") val borders: Borders? = null,
)

data class Rgb(
    @SerializedName("red") val red: Double,
    @SerializedName("green") val green: Double,
    @SerializedName("blue") val blue: Double,
)

data class TextFormat(
    @SerializedName("foregroundColor") val foregroundColor: Rgb? = null,
    @SerializedName("fontSize") val fontSize: Int? = null,
    @SerializedName("bold") val bold: Boolean? = null,
    @SerializedName("italic") val italic: Boolean? = null,
)

data class Borders(
    @SerializedName("top") val top: Border? = null,
    @SerializedName("bottom") val bottom: Border? = null,
    @SerializedName("left") val left: Border? = null,
    @SerializedName("right") val right: Border? = null,
)

data class Border(
    @SerializedName("style") val style: String = "SOLID",
    @SerializedName("color") val color: Rgb,
)

data class UpdateSheetPropertiesRequest(
    @SerializedName("properties") val properties: SheetPropertiesUpdate,
    @SerializedName("fields") val fields: String,
)

data class SheetPropertiesUpdate(
    @SerializedName("sheetId") val sheetId: Int? = null,
    @SerializedName("index") val index: Int? = null,
    @SerializedName("tabColor") val tabColor: Rgb? = null,
    @SerializedName("gridProperties") val gridProperties: GridPropertiesUpdate? = null,
)

data class GridPropertiesUpdate(
    @SerializedName("hidden") val hidden: Boolean? = null,
    @SerializedName("frozenRowCount") val frozenRowCount: Int? = null,
)

data class AutoResizeDimensionsRequest(
    @SerializedName("dimensions") val dimensions: DimensionRange,
)

data class DimensionRange(
    @SerializedName("sheetId") val sheetId: Int,
    @SerializedName("dimension") val dimension: String = "COLUMNS",
    @SerializedName("startIndex") val startIndex: Int,
    @SerializedName("endIndex") val endIndex: Int,
)

data class UpdateDimensionPropertiesRequest(
    @SerializedName("range") val range: DimensionRange,
    @SerializedName("properties") val properties: DimensionsProperties,
    @SerializedName("fields") val fields: String,
)

data class DimensionsProperties(
    @SerializedName("pixelSize") val pixelSize: Int? = null,
)