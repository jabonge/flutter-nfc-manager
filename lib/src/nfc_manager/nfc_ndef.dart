import 'dart:convert' show utf8, ascii;
import 'dart:typed_data';

/// Represents the NDEF Message that is specified by the NFC Forum.
class NdefMessage {
  NdefMessage(this.records);

  final List<NdefRecord> records;

  int get byteLength => records.isEmpty
    ? 0
    : records.map((e) => e.byteLength).reduce((x, y) => x+y);
}

/// Represents the NDEF Record that is specified by the NFC Forum.
class NdefRecord {
  static const URI_PREFIX_LIST = [
    '',
    'http://www.',
    'https://www.',
    'http://',
    'https://',
    'tel:',
    'mailto:',
    'ftp://anonymous:anonymous@',
    'ftp://ftp.',
    'ftps://',
    'sftp://',
    'smb://',
    'nfs://',
    'ftp://',
    'dav://',
    'news:',
    'telnet://',
    'imap:',
    'rtsp://',
    'urn:',
    'pop:',
    'sip:',
    'sips:',
    'tftp:',
    'btspp://',
    'btl2cap://',
    'btgoep://',
    'tcpobex://',
    'irdaobex://',
    'file://',
    'urn:epc:id:',
    'urn:epc:tag:',
    'urn:epc:pat:',
    'urn:epc:raw:',
    'urn:epc:',
    'urn:nfc:',
  ];

  static const TNF_EMPTY = 0x00;
  static const TNF_WELL_KNOWN = 0x01;
  static const TNF_MIME = 0x02;
  static const TNF_ABSOLUTE_URI = 0x03;
  static const TNF_EXTERNAL = 0x04;
  static const TNF_UNKNOWN = 0x05;
  static const TNF_UNCHANGED = 0x06;
  static const TNF_RESERVE = 0x07;

  static const RTD_TEXT = [0x54];
  static const RTD_URI = [0x55];
  static const RTD_SMART_POSTER = [0x53, 0x70];
  static const RTD_ALTERNATIVE_CARRIER = [0x61, 0x63];
  static const RTD_HANDOVER_CARRIER = [0x48, 0x63];
  static const RTD_HANDOVER_REQUEST = [0x48, 0x72];
  static const RTD_HANDOVER_SELECT = [0x48, 0x73];

  NdefRecord._(
    this.typeNameFormat,
    this.type,
    this.identifier,
    this.payload,
  );

  final int typeNameFormat;

  final Uint8List type;

  final Uint8List identifier;

  final Uint8List payload;

  /// Length in bytes that stored on this record.
  int get byteLength {
    int length = 3 + type.length + identifier.length + payload.length;

    // Not Short Record
    if (payload.length >= 256)
      length += 3;

    // ID Length
    if (typeNameFormat == TNF_EMPTY || identifier.length > 0)
      length += 1;

    return length;
  }

  /// Create an NDEF record from its component fields.
  ///
  /// Recommended to use other factory constructors such as `createExternalRecord` where possible,
  /// since they perform validation that the record is correctly formatted as NDEF.
  /// However if you know what you are doing then this constructor offers the most flexibility.
  factory NdefRecord({
    int typeNameFormat,
    Uint8List type,
    Uint8List identifier,
    Uint8List payload,
  }) {
    Uint8List _type = type ?? Uint8List.fromList([]);
    Uint8List _identifier = identifier ?? Uint8List.fromList([]);
    Uint8List _payload = payload ?? Uint8List.fromList([]);

    _validateFormat(typeNameFormat, _type, _identifier, _payload);

    return NdefRecord._(typeNameFormat, _type, _identifier, _payload);
  }

  /// Create an NDEF record containing external (applicattion-specific) data.
  factory NdefRecord.createExternal(String domain, String type, Uint8List data) {
    if (domain == null)
      throw('domain is null');
    if (type == null)
      throw('type is null');

    String _domain = domain.trim().toLowerCase();
    String _type = type.trim().toLowerCase();

    if (_domain.isEmpty)
      throw('domain is empty');
    if (_type.isEmpty)
      throw('type is empty');

    List<int> domainBytes = utf8.encode(_domain);
    List<int> typeBytes = utf8.encode(_type);
    List<int> bytes = domainBytes + ':'.codeUnits + typeBytes;

    return NdefRecord(
      typeNameFormat: TNF_EXTERNAL,
      type: Uint8List.fromList(bytes),
      identifier: null,
      payload: data,
    );
  }

  /// Create an NDEF record containing a mime data.
  factory NdefRecord.createMime(String type, Uint8List data) {
    if (type == null)
      throw('type is null');
    String normalized = type.toLowerCase().trim().split(';').first;
    if (normalized.isEmpty)
      throw('type is empty');

    int slashIndex = normalized.indexOf('/');
    if (slashIndex == 0)
      throw('type must have major type');
    if (slashIndex == normalized.length - 1)
      throw('type must have minor type');

    return NdefRecord(
      typeNameFormat: TNF_MIME,
      type: ascii.encode(type),
      identifier: null,
      payload: data,
    );
  }

  /// Create an NDEF record containing a UTF-8 text.
  ///
  /// Can specify the `languageCode` for the provided text. The default is 'en'.
  factory NdefRecord.createText(String text, {String languageCode}) {
    if (text == null)
      throw('text is null');

    List<int> languageCodeBytes = ascii.encode(languageCode ?? 'en');
    if (languageCodeBytes.length >= 64)
      throw('languageCode is too long');

    List<int> textBytes = languageCodeBytes + utf8.encode(text);

    return NdefRecord(
      typeNameFormat: TNF_WELL_KNOWN,
      type: Uint8List.fromList(RTD_TEXT),
      identifier: null,
      payload: Uint8List.fromList([languageCodeBytes.length] + textBytes),
    );
  }

  /// Create an NDEF record containing a uri.
  factory NdefRecord.createUri(Uri uri) {
    if (uri == null)
      throw('uri is null');

    String uriString = uri.normalizePath().toString();
    if (uriString.length < 1)
      throw('uri is empty');

    int prefixIndex = URI_PREFIX_LIST.indexWhere((e) => uriString.startsWith(e), 1);
    if (prefixIndex < 0) prefixIndex = 0;

    List<int> uriBytes = utf8.encode(
      uriString.substring(URI_PREFIX_LIST[prefixIndex].length),
    );

    return NdefRecord(
      typeNameFormat: TNF_WELL_KNOWN,
      type: Uint8List.fromList(RTD_URI),
      identifier: null,
      payload: Uint8List.fromList([prefixIndex] + uriBytes),
    );
  }
}

void _validateFormat(int format, Uint8List type, Uint8List identifier, Uint8List payload) {
  switch (format) {
    case NdefRecord.TNF_EMPTY:
      if (type.isNotEmpty || identifier.isNotEmpty || payload.isNotEmpty)
        throw('unexpected data in EMPTY record');
      break;
    case NdefRecord.TNF_WELL_KNOWN:
    case NdefRecord.TNF_MIME:
    case NdefRecord.TNF_ABSOLUTE_URI:
    case NdefRecord.TNF_EXTERNAL:
      break;
    case NdefRecord.TNF_UNKNOWN:
    case NdefRecord.TNF_RESERVE:
      if (type.isNotEmpty)
        throw('unexpected type field in UNKNOWN or RESERVE record');
      break;
    case NdefRecord.TNF_UNCHANGED:
      throw('unexpected UNCHANGED in first chunk or logical record');
    default:
      throw('unexpected format value: $format');
  }
}
