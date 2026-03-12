package ec.cusoft.facturaec.filewriter;

import java.io.File;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.database.ConnectionProvider;
import org.openbravo.model.ad.access.InvoiceLineTax;
import org.openbravo.model.common.enterprise.Organization;
import org.openbravo.model.common.enterprise.OrganizationType;
import org.openbravo.model.common.geography.CountryTrl;
import org.openbravo.model.common.invoice.Invoice;
import org.openbravo.model.common.invoice.InvoiceLine;
import org.openbravo.model.common.invoice.InvoiceTax;
import org.openbravo.model.common.plm.ProductBOM;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentSchedule;
import org.openbravo.model.financialmgmt.payment.FIN_PaymentScheduleDetail;
import org.openbravo.model.financialmgmt.tax.TaxRate;
import org.openbravo.service.db.DalConnectionProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import ec.cusoft.facturaec.EEIParamFacturae;
import ec.cusoft.facturaec.EeiBankAccount;
import ec.cusoft.facturaec.ad_process.webservices.util.ClientSOAP;
import ec.cusoft.facturaec.generador.ECWSClient;
import ec.cusoft.facturaec.templates.OBEInvoice_I;
import ec.cusoft.facturaec.templates.OBWSEInvoice_I;
import ec.cusoft.refund.eeirRefund;

public class FileGenerationEcuador extends AbstractFileGeneration
    implements OBEInvoice_I, OBWSEInvoice_I {

  static Logger log4j = Logger.getLogger(FileGenerationEcuador.class);

  public String generateFile(Invoice invoice, String rootDirectory, String lang) throws Exception {
    String strDocType = invoice.getDocumentType().getEeiEdocType().toString().replaceAll("\\s", "");
    boolean boolIsEDoc = invoice.getDocumentType().isEeiIsEdoc();

    if (!boolIsEDoc) {
      throw new OBException(
          "No es posible generar Documento Electrónico,la parametrización del tipo de documento no es válida.");
    }
    if (!strDocType.trim().equals("01")) {
      throw new OBException("Tipo de documento electrónico no configurado como Factura de Venta.");
    }
    DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

    OBCriteria<EEIParamFacturae> objParams = OBDal.getInstance()
        .createCriteria(EEIParamFacturae.class);
    objParams.add(Restrictions.eq(EEIParamFacturae.PROPERTY_ACTIVE, true));

    String strXmlVersion = objParams.list().get(0).getXMLVersion();
    BigDecimal maxDate = objParams.list().get(0).getMAXDateGeneration();
        if(maxDate == null) {
        	throw new OBException("Configure el campo Tiempo maximo generacion Doc.Elec. en la ventana Parametros FE");
        }else {
            Date invoiceDate = invoice.getInvoiceDate();
            Date currentDate = new Date(); // Fecha actual

            long differenceInMillis = currentDate.getTime() - invoiceDate.getTime();
            long hoursDifference = differenceInMillis / (1000 * 60 * 60);
            
            if (hoursDifference > maxDate.intValue()) {
                throw new OBException("Fecha de generación extemporánea");
        }
    }

    if (!strXmlVersion.equals("1.0.0") && !strXmlVersion.equals("1.1.0")) {
      throw new OBException(
          "Versión de XML inválida. Por favor configure la versión (1.0.0 - 1.1.0)");
    }

    // root elements
    Document doc = docBuilder.newDocument();
    doc.setXmlStandalone(true);
    Element rootElement = doc.createElement("factura");
    rootElement.setAttribute("id", "comprobante");
    rootElement.setAttribute("version", strXmlVersion);
    doc.appendChild(rootElement);

    // infoTributaria elements
    Element infoTributaria = doc.createElement("infoTributaria");
    rootElement.appendChild(infoTributaria);

    // TIPO DE EMISIÓN
    Element tipoEmision = doc.createElement("tipoEmision");
    tipoEmision.appendChild(doc.createTextNode("1")); // 1 normal - 2
    // indisponibilidad
    // del
    // sistema
    infoTributaria.appendChild(tipoEmision);

    // RUC
    String strRuc = invoice.getOrganization().getOrganizationInformationList().get(0).getTaxID();
    if (!strRuc.matches("^\\d{13}$")) {
      throw new OBException("El formato del RUC es incorrecto.");
    }
    Element ruc = doc.createElement("ruc");
    ruc.appendChild(doc.createTextNode(strRuc));
    infoTributaria.appendChild(ruc);

    // CLAVE DE ACCESO
    boolean boolKeyAccessGenerate = (ClientSOAP.SelectParams(3).equals("Y") ? true : false);

    // New Name - Description
    boolean strExtract = (ClientSOAP.SelectParams(4).equals("Y") ? true : false);

    if (boolKeyAccessGenerate) {
      String strKeyAccess = null;
      strKeyAccess = invoice.getEeiCodigo();
      if (strKeyAccess == null || strKeyAccess.equals("")) {
        throw new OBException("Clave de acceso no encontrada.");
      }
      if (strKeyAccess.length() != 49) {
        throw new OBException("Extensión de clave de acceso no válida (49 dígitos).");
      }
      Element keyAccess = doc.createElement("claveAcceso");
      keyAccess.appendChild(doc.createTextNode(strKeyAccess));
      infoTributaria.appendChild(keyAccess);
    }

    // TIPO DE DOCUMENTO
    String strCodDoc = invoice.getDocumentType().getEeiEdocType();
    Element codDoc = doc.createElement("codDoc");
    codDoc.appendChild(doc.createTextNode(strCodDoc));
    infoTributaria.appendChild(codDoc);

    // NÚMERO DE DOCUMENTO
    if (invoice.getDocumentNo().length() < 8) {
      throw new OBException("Formato de número de documento inválido. (Prefijo 000-000-).");
    }
    String strSubDocumentNo = invoice.getDocumentNo().substring(8);
    while (strSubDocumentNo.length() < 9) {
      strSubDocumentNo = "0" + strSubDocumentNo;
    }

    String strSubDocumentNo1 = truncate(invoice.getDocumentNo(), 8);
    String documentnoX = strSubDocumentNo1 + strSubDocumentNo;
    String[] documentno = null;

    log4j.debug(documentnoX);

    if (documentnoX.matches("^\\d{3}-\\d{3}-\\d{9}$")) {
      documentno = documentnoX.split("-");
    } else {
      throw new OBException(
          "El formato del número de documento es incorrecto (000-000-000000000).");
    }
    Element estab = doc.createElement("estab");
    estab.appendChild(doc.createTextNode(documentno[0]));
    infoTributaria.appendChild(estab);

    Element ptoEmi = doc.createElement("ptoEmi");
    ptoEmi.appendChild(doc.createTextNode(documentno[1]));
    infoTributaria.appendChild(ptoEmi);

    Element secuencial = doc.createElement("secuencial");
    secuencial.appendChild(doc.createTextNode(documentno[2]));
    infoTributaria.appendChild(secuencial);

    // AGENTE DE RETENCIÓN - MICROEMPRESAS
    OBCriteria<Organization> ObjOrganization = OBDal.getInstance()
        .createCriteria(Organization.class);
    OrganizationType objOrganizationType = OBDal.getInstance().get(OrganizationType.class, "1");
    ObjOrganization
        .add(Restrictions.eq(Organization.PROPERTY_ORGANIZATIONTYPE, objOrganizationType));

    String strMicroBusiness = null;
    String strWithholdingAgent = null;
    String strRegimeRimpe=null;

    if (ObjOrganization.list().size() > 0) {
    	strMicroBusiness = ObjOrganization.list().get(0).getEeiMicroBusiness();
    	strWithholdingAgent = ObjOrganization.list().get(0).getEeiWithholdingAgent();
    	strRegimeRimpe  = ObjOrganization.list().get(0).getEeiRimpe();
    }

    if (strMicroBusiness != null) {
        Element regimenMicroempresas = doc.createElement("regimenMicroempresas");
        regimenMicroempresas.appendChild(doc.createTextNode(strMicroBusiness));
        infoTributaria.appendChild(regimenMicroempresas);
    }
    
    if (strWithholdingAgent != null) {
        Element agenteRetencion = doc.createElement("agenteRetencion");
        agenteRetencion.appendChild(doc.createTextNode(strWithholdingAgent));
        infoTributaria.appendChild(agenteRetencion);
    }
    if (strRegimeRimpe != null) {
	    Element contribuyenteRimpe = doc.createElement("contribuyenteRimpe");
	    contribuyenteRimpe.appendChild(doc.createTextNode(strRegimeRimpe));
	    infoTributaria.appendChild(contribuyenteRimpe);
    }
    // DIRECCIÓN ESTABLECIMIENTO
    String headquartersCountry = null;
    try {
      OBContext.setAdminMode(true);
      for (CountryTrl countryTrl : invoice.getOrganization().getOrganizationInformationList().get(0)
          .getLocationAddress().getCountry().getCountryTrlList()) {
        if (countryTrl.getLanguage().getLanguage().equals(lang)) {
          headquartersCountry = countryTrl.getName();
        }
      }
    } catch (NullPointerException e) {
      throw new OBException("La Organización no tiene dirección.");
    } finally {
      OBContext.restorePreviousMode();
    }

    if (headquartersCountry == null) {
      headquartersCountry = invoice.getOrganization().getOrganizationInformationList().get(0)
          .getLocationAddress().getCountry().getName();
    }

    String headQuartersaddress = (invoice.getOrganization().getOrganizationInformationList().get(0)
        .getLocationAddress().getAddressLine1() == null
            ? " "
            : invoice.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
                .getAddressLine1())
        + "--"
        + (invoice.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
            .getAddressLine2() == null
                ? " "
                : invoice.getOrganization().getOrganizationInformationList().get(0)
                    .getLocationAddress().getAddressLine2())
        + "--"
        + (invoice.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
            .getPostalCode() == null
                ? " "
                : invoice.getOrganization().getOrganizationInformationList().get(0)
                    .getLocationAddress().getPostalCode())
        + "--"
        + (invoice.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
            .getCityName() == null
                ? " "
                : invoice.getOrganization().getOrganizationInformationList().get(0)
                    .getLocationAddress().getCityName())
        + "--"
        + (invoice.getOrganization().getOrganizationInformationList().get(0).getLocationAddress()
            .getRegion() == null ? " "
                : invoice.getOrganization().getOrganizationInformationList().get(0)
                    .getLocationAddress().getRegion().getName())
        + "--" + headquartersCountry;

    Element infoFactura = doc.createElement("infoFactura");
    rootElement.appendChild(infoFactura);

    Element fechaEmision = doc.createElement("fechaEmision");
    SimpleDateFormat ecFormat = new SimpleDateFormat("dd/MM/yyyy");
    fechaEmision.appendChild(doc.createTextNode(ecFormat.format(invoice.getInvoiceDate())));
    infoFactura.appendChild(fechaEmision);

    String country = null;
    try {
      OBContext.setAdminMode(true);
      for (CountryTrl countryTrl : invoice.getPartnerAddress().getLocationAddress().getCountry()
          .getCountryTrlList()) {
        if (countryTrl.getLanguage().getLanguage().equals(lang)) {
          country = countryTrl.getName();
        }
      }
    } finally {
      OBContext.restorePreviousMode();
    }

    if (country == null) {
      country = invoice.getPartnerAddress().getLocationAddress().getCountry().getName();
    }

    String address = (invoice.getPartnerAddress().getLocationAddress().getAddressLine1() == null
        ? " "
        : invoice.getPartnerAddress().getLocationAddress().getAddressLine1())
        + "--"
        + (invoice.getPartnerAddress().getLocationAddress().getAddressLine2() == null ? " "
            : invoice.getPartnerAddress().getLocationAddress().getAddressLine2())
        + "--"
        + (invoice.getPartnerAddress().getLocationAddress().getPostalCode() == null ? " "
            : invoice.getPartnerAddress().getLocationAddress().getPostalCode())
        + "--"
        + (invoice.getPartnerAddress().getLocationAddress().getCityName() == null ? " "
            : invoice.getPartnerAddress().getLocationAddress().getCityName())
        + "--"
        + (invoice.getPartnerAddress().getLocationAddress().getRegion() == null ? " "
            : invoice.getPartnerAddress().getLocationAddress().getRegion().getName())
        + "--" + country;

    if (headQuartersaddress == null) {
      throw new OBException("Dirección de Establecimiento es nula.");
    } else {
      headQuartersaddress = headQuartersaddress.replaceAll("[\n]", " ");
    }
    Element dirEstablecimiento = doc.createElement("dirEstablecimiento");
    dirEstablecimiento.appendChild(doc.createTextNode(truncate(headQuartersaddress, 300)));
    infoFactura.appendChild(dirEstablecimiento);

    String idType=null;

    // TIPO DE DOCUMENTO
    if (invoice.getBusinessPartner().getSswhTaxidtype().equals("R")) {
    	idType = "04";
    } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("D")) {
    	idType = "05";
    } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("P")) {
    	idType = "06";
    } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("EEI_C")) {
    	idType = "07";
    } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("EEI_E")) {    	
    	idType = "08";
    }


    Element tipoIdentificacionComprador = doc.createElement("tipoIdentificacionComprador");
    tipoIdentificacionComprador.appendChild(doc.createTextNode(idType));

    infoFactura.appendChild(tipoIdentificacionComprador);

    // *****GUÍAS DE REMISIÓN
    if (invoice.getOrganization().isEeiShowRemissionGuide()) {

      String strInvoiceLine = SelectFirstLine(invoice.getId());

      if (strInvoiceLine != null && !strInvoiceLine.equals("")) {
        InvoiceLine objFirstLine = OBDal.getInstance().get(InvoiceLine.class, strInvoiceLine);
        if (objFirstLine.getGoodsShipmentLine() != null) {
          String strGuiaRemision = null;
          try {
            strGuiaRemision = objFirstLine.getGoodsShipmentLine().getShipmentReceipt()
                .getDocumentNo().substring(8);
          } catch (Exception e) {
            throw new OBException(
                "El formato del número de documento de la referencia (Guía de Remisión) es incorrecto (000-000-000000000).");
          }

          while (strGuiaRemision.length() < 9) {
            strGuiaRemision = "0" + strGuiaRemision;
          }

          log4j.debug("parte 2    " + strGuiaRemision);

          String strSubGuiaRemision = truncate(
              objFirstLine.getGoodsShipmentLine().getShipmentReceipt().getDocumentNo(), 8);

          log4j.debug("parte 1   " + strSubGuiaRemision);

          String strdocumentnoX = strSubGuiaRemision + strGuiaRemision;
          String[] strdocumentno = null;

          log4j.debug(strdocumentnoX);

          if (strdocumentnoX.matches("^\\d{3}-\\d{3}-\\d{9}$")) {
            strdocumentno = strdocumentnoX.split("-");
          } else {
            throw new OBException(
                "El formato del número de documento de la referencia (Guía de Remisión) es incorrecto (000-000-000000000).");
          }
          Element guiaRemision = doc.createElement("guiaRemision");
          guiaRemision.appendChild(doc.createTextNode(strdocumentnoX));
          infoFactura.appendChild(guiaRemision);
        }
      }
    }
    // ***GUÍAS DE REMISIÓN

    String strDescription2 = invoice.getBusinessPartner().getDescription() == null ? "ND"
        : invoice.getBusinessPartner().getDescription();
    String strName2 = strExtract && !strDescription2.equals("ND") ? strDescription2
        : invoice.getBusinessPartner().getName();
    if (strName2 == null || strName2.trim().equals("")) {
      throw new OBException("El cliente no tiene nombre fiscal.");
    }
    strName2 = truncate(strName2, 300);
    Element razonSocialComprador = doc.createElement("razonSocialComprador");
    razonSocialComprador.appendChild(doc.createTextNode(strName2));
    infoFactura.appendChild(razonSocialComprador);

    String strTaxid = invoice.getBusinessPartner().getTaxID();
    if (strTaxid == null || strTaxid.trim().equals("")) {
      throw new OBException("El cliente no tiene identificación (CIF/NIF).");
    }

    Element identificacionComprador = doc.createElement("identificacionComprador");

    identificacionComprador.appendChild(doc.createTextNode(strTaxid));
    infoFactura.appendChild(identificacionComprador);

    // **** FACTURAS NEGOCIABLES CAMPO DIRECCIONCOMPRADOR
    if (invoice.getTransactionDocument().isEeiComercialInv()) {

      Element direccionComprador = doc.createElement("direccionComprador");
      direccionComprador
          .appendChild(doc.createTextNode(invoice.getPartnerAddress().getLocationAddress().getAddressLine1()
              + " - " + invoice.getPartnerAddress().getLocationAddress().getCityName()));
      infoFactura.appendChild(direccionComprador);
    }

    DecimalFormatSymbols simbolos = new DecimalFormatSymbols();
    simbolos.setDecimalSeparator('.');
    DecimalFormat formateador = new DecimalFormat("#########0.00", simbolos);

    boolean isTip = false;
    BigDecimal amountTip = BigDecimal.ZERO;
    double TotalSinImpuestos = 0;
    for (InvoiceTax impuestoObj : invoice.getInvoiceTaxList()) {
      // Verificar si existe Rango de Impuesto con Propina y activar tag propina
      if (impuestoObj.getTax().isEeiTip()) {
        amountTip = impuestoObj.getTaxAmount();
      }
      TotalSinImpuestos = invoice.getSummedLineAmount().doubleValue();
    }

    Element totalSinImpuestos = doc.createElement("totalSinImpuestos");
    totalSinImpuestos
        .appendChild(doc.createTextNode(formateador.format(TotalSinImpuestos).toString()));
    infoFactura.appendChild(totalSinImpuestos);

    // ***********************************TOTAL DESCUENTOS

    double TotalDescuentos = 0;

    List<InvoiceLine> Linesnegatives = hasInvoiceLinesWithNegativeAmounts(
        invoice.getInvoiceLineList(), new BigDecimal(TotalSinImpuestos));

    List<InvoiceLine> Lines = invoice.getInvoiceLineList();

    if (Linesnegatives != null)
      Lines = Linesnegatives;

    boolean boolDiscountbypricelist = ObjOrganization.list().get(0).isEeiDiscountbypricelist();

    if (boolDiscountbypricelist) {

      for (InvoiceLine detalleObj : Lines) {

        double Cantidad = detalleObj.getInvoicedQuantity().doubleValue();

        double PrecioUnitario = detalleObj.getUnitPrice().doubleValue();

        double PrecioTarifa = detalleObj.getListPrice().doubleValue();

        double DescuentoLinea = 0;
        double PrecioTotalSinImpuesto = Double
            .parseDouble(formateador.format(detalleObj.getLineNetAmount().doubleValue()));
        if (PrecioTarifa > 0 & PrecioTarifa > PrecioUnitario) {
          PrecioUnitario = PrecioTarifa;
          DescuentoLinea = Double.parseDouble(
              formateador.format(Cantidad * PrecioUnitario).toString()) - PrecioTotalSinImpuesto;
        }
        if (DescuentoLinea > 0){
          TotalDescuentos = TotalDescuentos + DescuentoLinea;
        }
      }

    } else {

      for (InvoiceLine detalleObj : Lines) {

        double subtotalInicialLinea = 0;
        double DescuentoLinea = 0;
        subtotalInicialLinea = Double
            .parseDouble(formateador.format(detalleObj.getSseedInitialSubtotal()).toString());
        double subtotalFinal = 0;
        subtotalFinal = Double
            .parseDouble(formateador.format(detalleObj.getLineNetAmount()).toString());
        if (subtotalInicialLinea > subtotalFinal) {
          DescuentoLinea = subtotalInicialLinea - subtotalFinal;
        }
        if (DescuentoLinea > 0){
          TotalDescuentos = TotalDescuentos + DescuentoLinea;
        }
      }

    }

    Element totalDescuento = doc.createElement("totalDescuento");
    totalDescuento.appendChild(doc.createTextNode(formateador.format(TotalDescuentos).toString()));
    infoFactura.appendChild(totalDescuento);

    // ******************************************FIN DESCUENTOS

    // *****************************INICIO REEMBOLSOS

    String strRefundCode = null, strRefundTotal = null, strRefundBaseImponible = null,
        strRefundTotalImpuestos = null;
    if (getRefundData(invoice, "0").equals("Y")) {
      if (invoice.isSsreIsrefund()) {

        invoice.getEEIRRefundList();
    	
        for (eeirRefund invoice_refund : invoice.getEEIRRefundList()) {
          
        Invoice purchase_invoice = OBDal.getInstance().get(Invoice.class,
            invoice_refund.getEeirInvoice().getId());

        strRefundCode = getRefundData(invoice, "1");
        strRefundTotal = getRefundData(purchase_invoice, "2");
        strRefundBaseImponible = getRefundData(purchase_invoice, "3");
        strRefundTotalImpuestos = getRefundData(purchase_invoice, "4");

        log4j.debug(strRefundCode + " - " + strRefundTotal + " - " + strRefundBaseImponible + " - "
            + strRefundTotalImpuestos);

        if (strRefundCode != null && strRefundTotal != null && strRefundBaseImponible != null
            && strRefundTotalImpuestos != null) {

          try {
            Element codDocReembolso = doc.createElement("codDocReembolso");
            codDocReembolso.appendChild(doc.createTextNode(strRefundCode));
            infoFactura.appendChild(codDocReembolso);

            Element totalComprobantesReembolso = doc.createElement("totalComprobantesReembolso");
            totalComprobantesReembolso.appendChild(doc
                .createTextNode(formateador.format(Double.parseDouble(strRefundTotal)).toString()));
            infoFactura.appendChild(totalComprobantesReembolso);

            Element totalBaseImponibleReembolso = doc.createElement("totalBaseImponibleReembolso");
            totalBaseImponibleReembolso.appendChild(doc.createTextNode(
                formateador.format(Double.parseDouble(strRefundBaseImponible)).toString()));
            infoFactura.appendChild(totalBaseImponibleReembolso);

            Element totalImpuestoReembolso = doc.createElement("totalImpuestoReembolso");
            totalImpuestoReembolso.appendChild(doc.createTextNode(
                formateador.format(Double.parseDouble(strRefundTotalImpuestos)).toString()));
            infoFactura.appendChild(totalImpuestoReembolso);
          } catch (Exception e) {

            throw new OBException("Error al obtener información de reembolso. " + e.getMessage());
          }

        } else {
          String strError = null;
          if (strRefundCode == null || strRefundCode.trim().equals("")) {
            throw new OBException("Código de Reembolso en tipo de documento no configurado.");
          }
          if (strRefundCode == null || strRefundCode.trim().equals("")) {
            strError = "Total";
          }
          if (strRefundCode == null || strRefundCode.trim().equals("")) {
            strError = "Base Imponible";
          }
          if (strRefundCode == null || strRefundCode.trim().equals("")) {
            strError = "Total Impuestos";
          }
          throw new OBException("Error al obtener valor de " + strError
              + " de la factura de compra referenciada (reembolso).");
        }

      } 
      }else {
        throw new OBException("No hay una factura de compra referenciada válida (reembolso). ");
      }
    }
    // *****************************FIN REEMBOLSOS

    // *******************************************
    // infoFactura->totalConImpuestos elements
    Element totalConImpuestos = doc.createElement("totalConImpuestos");
    infoFactura.appendChild(totalConImpuestos);

    for (InvoiceTax impuestoObj : invoice.getInvoiceTaxList()) {
      // Verificar si existe Rango de Impuesto con Propina y activar tag propina
      if (impuestoObj.getTax().isEeiTip()) {
        isTip = true;
        amountTip = impuestoObj.getTaxAmount();
      }
      if (!isTip) {
        Element totalImpuesto = doc.createElement("totalImpuesto");
        totalConImpuestos.appendChild(totalImpuesto);

      /* CODIGO DEL IMPUESTO */
      Element codigo = doc.createElement("codigo");

      /*
       * if (impuestoObj.getTax().isSswhAtsIva().toString().equals("N") &&
       * impuestoObj.getTax().isSswhAtsSource().toString().equals("N"))
       * codigo.appendChild(doc.createTextNode("2")); else
       * codigo.appendChild(doc.createTextNode("3")); totalImpuesto.appendChild(codigo);
       */

      if (impuestoObj.getTax().isTaxdeductable()
          && !impuestoObj.getTax().getRate().toString().equals("0"))// (impuestoObj.getTax().getName().toString().equals("IVA
        // 12%
        // -
        // 01-01-2011"))
        codigo.appendChild(doc.createTextNode("2"));
      else if (impuestoObj.getTax().isTaxdeductable()
          && impuestoObj.getTax().getRate().toString().equals("0"))// (impuestoObj.getTax().getName().toString().equals("IVA
        // 0%
        // -
        // 01-01-2011"))
        codigo.appendChild(doc.createTextNode("2"));
      else if (impuestoObj.getTax().isSlplagIrbp()) {
        codigo.appendChild(doc.createTextNode("5"));
      } else {
        codigo.appendChild(doc.createTextNode("3"));
      }

      totalImpuesto.appendChild(codigo);
      /* FIN DE CODIGO DEL IMPUESTO */

      /* CODIGO DEL IMPUESTO RATE */
      Element codigoPorcentaje = doc.createElement("codigoPorcentaje");
      String codigoSriFeDet = ""; // tax.getTax().getEeiSriTaxcatCode().toString();
      if (impuestoObj.getTax() != null) {
        if (impuestoObj.getTax().getEeiSriTaxcatCode() == null) {
          String msg = "El impuesto " + impuestoObj.getTax().getName().toString()
              + " no tiene configurado el campo 'Código Impuesto SRI - FE'.";

          throw new OBException(msg);
        }
        if (impuestoObj.getTax().getEeiSriTaxcatCode() != null) {
          codigoSriFeDet = impuestoObj.getTax().getEeiSriTaxcatCode().toString();
        }
      }
      // if (impuestoObj.getTax().isTaxdeductable()
      // && !impuestoObj.getTax().getRate().toString().equals("0"))
      // codigoPorcentaje
      // .appendChild(doc.createTextNode((impuestoObj.getTax().getEeiSriTaxcatCode() != null)
      // ? impuestoObj.getTax().getEeiSriTaxcatCode().toString()
      // : ""));
      // else if (impuestoObj.getTax().isTaxdeductable()
      // && impuestoObj.getTax().getRate().toString().equals("0"))

      // codigoPorcentaje.appendChild(doc.createTextNode("0"));
      // else if (impuestoObj.getTax().isSlplagIrbp())
      // codigoPorcentaje
      // .appendChild(doc.createTextNode((impuestoObj.getTax().getEeiSriTaxcatCode() != null)
      // ? impuestoObj.getTax().getEeiSriTaxcatCode().toString()
      // : ""));

      // else
      // codigoPorcentaje.appendChild(doc.createTextNode("6"));

      codigoPorcentaje.appendChild(doc.createTextNode(codigoSriFeDet));
      totalImpuesto.appendChild(codigoPorcentaje);
      /* FIN DE CODIGO DEL IMPUESTO RATE */

      // Base imponible
      Element baseImponible = doc.createElement("baseImponible");

      if (impuestoObj.getTax().isSlplagIrbp()) {
        // Base impoible es la cantidad de unidades por producto.
        List<InvoiceLine> lines = invoice.getInvoiceLineList();
        double cantidadIrbp = 0;
        for (InvoiceLine detalleObjLn : lines) {
          OBCriteria<InvoiceLineTax> invlinetax = OBDal.getInstance()
              .createCriteria(InvoiceLineTax.class);
          invlinetax.add(Restrictions.eq(InvoiceLineTax.PROPERTY_INVOICELINE , detalleObjLn));
          invlinetax.add(Restrictions.eq(InvoiceLineTax.PROPERTY_TAX ,impuestoObj.getTax()));
          
          if (invlinetax.list().size() > 0) {
            List<InvoiceLineTax> lstinvlinetax = invlinetax.list();
            for (InvoiceLineTax linestax : lstinvlinetax) {
              double doubleTaxableAmount =  linestax.getTaxableAmount().doubleValue(); ;
              cantidadIrbp = cantidadIrbp + doubleTaxableAmount;
            }           
          }
        }
	        baseImponible
	        .appendChild(doc.createTextNode(formateador.format(cantidadIrbp).toString()));
	    totalImpuesto.appendChild(baseImponible);
        } else {
          double BaseImp = impuestoObj.getTaxableAmount().doubleValue();
          baseImponible.appendChild(doc.createTextNode(formateador.format(BaseImp).toString()));
          totalImpuesto.appendChild(baseImponible);
        }

        Element tarifa = doc.createElement("tarifa");
        double Tarifa = impuestoObj.getTax().getRate().doubleValue();

        if (impuestoObj.getTax().isSlplagIrbp()) {
          // irbp
          String numberString = Double.toString(Tarifa);
          char firsDigit = numberString.charAt(0);
          int myInt = Integer.parseInt(String.valueOf(firsDigit));
          tarifa.appendChild(doc.createTextNode(formateador.format(myInt).toString()));
          totalImpuesto.appendChild(tarifa);
        } else {
          tarifa.appendChild(doc.createTextNode(formateador.format(Tarifa).toString()));
          totalImpuesto.appendChild(tarifa);
        }

        double Valor = impuestoObj.getTaxAmount().doubleValue();
        Element valor = doc.createElement("valor");
        valor.appendChild(doc.createTextNode(formateador.format(Valor).toString()));
        totalImpuesto.appendChild(valor);
      }

    }
    // Tag Propina ( solo si existe impuesto con check Propina marcado
    if (isTip) {
      Element propina = doc.createElement("propina");
      propina.appendChild(doc.createTextNode(amountTip.toString())); // 0 por defecto
      infoFactura.appendChild(propina);
    }

    double ImporteTotal = invoice.getGrandTotalAmount().doubleValue();
    Element importeTotal = doc.createElement("importeTotal");
    // importeTotal.appendChild(doc.createTextNode(invoice.getGrandTotalAmount().toPlainString()));
    importeTotal.appendChild(doc.createTextNode(formateador.format(ImporteTotal).toString()));
    infoFactura.appendChild(importeTotal);

    String strMoneda;
    if (invoice.getCurrency().getISOCode().equals("USD")) {
      strMoneda = "DOLAR";
    } else if (invoice.getCurrency().getISOCode().equals("EUR")) {
      strMoneda = "EURO";
    } else {
      strMoneda = "DOLAR";
    }
    Element moneda = doc.createElement("moneda");
    moneda.appendChild(doc.createTextNode(strMoneda));
    infoFactura.appendChild(moneda);

    // *************************INICIA PAGOS
    /** FORMAS DE PAGOS - CC **/

    int intPaymentScheduleDatail = 0;
    String strFinPaymentScheduleID = "ND";
    for (FIN_PaymentSchedule paymentSchedule2 : invoice.getFINPaymentScheduleList()) {
      for (FIN_PaymentScheduleDetail psd2 : paymentSchedule2
          .getFINPaymentScheduleDetailInvoicePaymentScheduleList()) {

        try {
          strFinPaymentScheduleID = psd2.getPaymentDetails().getId() == null ? "ND"
              : psd2.getPaymentDetails().getId();
        } catch (Exception e) {
        }

        if (!strFinPaymentScheduleID.equals("ND")) {
          intPaymentScheduleDatail++;
        }
      }
    }

    if (intPaymentScheduleDatail > 0) {

      Element pagos = doc.createElement("pagos");

      Element pago = null;

      Element pagos2 = doc.createElement("pagos");

      Element pago2 = null;

      double dbdTotalPaymentOut = 0;
      BigDecimal bgdGrandTotalPayment = BigDecimal.ZERO;

      String strPaymentMethod = "ND";

      String strReviewPaymentMethod = "ND";

      for (FIN_PaymentSchedule paymentSchedule : invoice.getFINPaymentScheduleList()) {

        List<FIN_PaymentSchedule> lstPaymentSchedule = invoice.getFINPaymentScheduleList();

        if (lstPaymentSchedule.size() > 0) {

          for (FIN_PaymentScheduleDetail psd : paymentSchedule
              .getFINPaymentScheduleDetailInvoicePaymentScheduleList()) {

            String strCodePaymentMethod = "ND";

            try {

              pago = doc.createElement("pago");

              Element formaPago = doc.createElement("formaPago");

              try {
                strReviewPaymentMethod = psd.getPaymentDetails().getId() == null ? "ND"
                    : psd.getPaymentDetails().getId();
              } catch (Exception e1) {
              }
              if (strReviewPaymentMethod.equals("ND")) {

                strCodePaymentMethod = "";

              } else {

                strPaymentMethod = psd.getPaymentDetails().getFinPayment().getPaymentMethod()
                    .getId() == null ? "ND"
                        : psd.getPaymentDetails().getFinPayment().getPaymentMethod().getId();

                strCodePaymentMethod = psd.getPaymentDetails().getFinPayment().getPaymentMethod()
                    .getEeiCodeEi() == null ? "ND"
                        : psd.getPaymentDetails().getFinPayment().getPaymentMethod().getEeiCodeEi();

                formaPago.appendChild(doc.createTextNode(strCodePaymentMethod));
                pago.appendChild(formaPago);

                Element total = doc.createElement("total");
                double dblpagos = psd.getAmount().doubleValue();
                total.appendChild(doc.createTextNode(formateador.format(dblpagos).toString()));
                pago.appendChild(total);

                if (invoice.getPaymentMethod().getId()
                    .equals(psd.getPaymentDetails().getFinPayment().getPaymentMethod().getId())) {

                  Element plazo = doc.createElement("plazo");

                  String strUnitTime = "";
                  if (invoice.getPaymentTerms().getOverduePaymentDaysRule() > 0) {

                    double dblplazo = invoice.getPaymentTerms().getOverduePaymentDaysRule();
                    plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
                    pago.appendChild(plazo);

                    Element unidadTiempo = doc.createElement("unidadTiempo");
                    strUnitTime = "dias";
                    unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
                    pago.appendChild(unidadTiempo);

                  } else if (invoice.getPaymentTerms().getOffsetMonthDue() > 0) {

                    double dblplazo = invoice.getPaymentTerms().getOffsetMonthDue();
                    plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
                    pago.appendChild(plazo);

                    Element unidadTiempo = doc.createElement("unidadTiempo");

                    strUnitTime = "meses";
                    unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
                    pago.appendChild(unidadTiempo);
                  } else if ((invoice.getPaymentTerms().getOffsetMonthDue() == 0
                      && invoice.getPaymentTerms().getOverduePaymentDaysRule() == 0)) {
                    if (invoice.getTransactionDocument().isEeiComercialInv()) {

                      plazo.appendChild(doc.createTextNode(String.valueOf(0)));
                      pago.appendChild(plazo);

                      Element unidadTiempo = doc.createElement("unidadTiempo");

                      strUnitTime = "dias";
                      unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
                      pago.appendChild(unidadTiempo);
                    }
                  }
                }
                pagos.appendChild(pago);

                dbdTotalPaymentOut = dbdTotalPaymentOut + psd.getAmount().doubleValue();

              }
            } catch (Exception e) {
            }
          }

        }

        bgdGrandTotalPayment = new BigDecimal(dbdTotalPaymentOut);
        bgdGrandTotalPayment = bgdGrandTotalPayment.setScale(2, RoundingMode.HALF_UP);
      }

      BigDecimal bgbGranTotalInvoice = invoice.getGrandTotalAmount().setScale(2,
          RoundingMode.HALF_UP);

      if (!bgbGranTotalInvoice.equals(bgdGrandTotalPayment)) {

        if (!strPaymentMethod.equals(invoice.getPaymentMethod().getId())) {
          pagos = doc.createElement("pagos");

          pago = doc.createElement("pago");

          Element formaPago = doc.createElement("formaPago");
          String strCodePaymentMethod = invoice.getPaymentMethod().getEeiCodeEi() == null ? "ND"
              : invoice.getPaymentMethod().getEeiCodeEi();
          if (strCodePaymentMethod.equals("ND")) {
            throw new OBException("El método de Pago " + invoice.getPaymentMethod().getName()
                + " no esta configurado para el proceso de Facturación Electrónica");

          }
          formaPago.appendChild(doc.createTextNode(strCodePaymentMethod));
          pago.appendChild(formaPago);

          Element total = doc.createElement("total");
          double dblpagos = invoice.getGrandTotalAmount().doubleValue();
          total.appendChild(doc.createTextNode(formateador.format(dblpagos).toString()));
          pago.appendChild(total);

          Element plazo = doc.createElement("plazo");

          String strUnitTime = "";
          if (invoice.getPaymentTerms().getOverduePaymentDaysRule() > 0) {

            double dblplazo = invoice.getPaymentTerms().getOverduePaymentDaysRule();
            plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
            pago.appendChild(plazo);

            Element unidadTiempo = doc.createElement("unidadTiempo");
            strUnitTime = "dias";
            unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
            pago.appendChild(unidadTiempo);
          } else if (invoice.getPaymentTerms().getOffsetMonthDue() > 0) {

            double dblplazo = invoice.getPaymentTerms().getOffsetMonthDue();
            plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
            pago.appendChild(plazo);

            Element unidadTiempo = doc.createElement("unidadTiempo");

            strUnitTime = "meses";
            unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
            pago.appendChild(unidadTiempo);
          } else if ((invoice.getPaymentTerms().getOffsetMonthDue() == 0
              && invoice.getPaymentTerms().getOverduePaymentDaysRule() == 0)) {
            if (invoice.getTransactionDocument().isEeiComercialInv()) {

              plazo.appendChild(doc.createTextNode(String.valueOf(0)));
              pago.appendChild(plazo);

              Element unidadTiempo = doc.createElement("unidadTiempo");

              strUnitTime = "dias";
              unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
              pago.appendChild(unidadTiempo);
            }
          }

          pagos.appendChild(pago);

        } else {

          pago2 = (doc.createElement("pago"));

          Element formaPago = doc.createElement("formaPago");

          String strCodePaymentMethod = invoice.getPaymentMethod().getEeiCodeEi() == null ? "ND"
              : invoice.getPaymentMethod().getEeiCodeEi();
          if (strCodePaymentMethod.equals("ND")) {
            throw new OBException("El método de Pago " + invoice.getPaymentMethod().getName()
                + " no esta configurado para el proceso de Facturación Electrónica");

          }
          formaPago.appendChild(doc.createTextNode(strCodePaymentMethod));
          pago2.appendChild(formaPago);

          Element total = doc.createElement("total");
          double dblpagos = invoice.getGrandTotalAmount().doubleValue();
          total.appendChild(doc.createTextNode(formateador.format(dblpagos).toString()));
          pago2.appendChild(total);

          Element plazo = doc.createElement("plazo");

          String strUnitTime = "";

          if (invoice.getPaymentTerms().getOverduePaymentDaysRule() > 0) {

            double dblplazo = invoice.getPaymentTerms().getOverduePaymentDaysRule();
            plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
            pago2.appendChild(plazo);

            Element unidadTiempo = doc.createElement("unidadTiempo");
            strUnitTime = "dias";
            unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
            pago2.appendChild(unidadTiempo);
          } else if (invoice.getPaymentTerms().getOffsetMonthDue() > 0) {

            double dblplazo = invoice.getPaymentTerms().getOffsetMonthDue();
            plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
            pago2.appendChild(plazo);

            Element unidadTiempo = doc.createElement("unidadTiempo");

            strUnitTime = "meses";
            unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
            pago2.appendChild(unidadTiempo);
          } else if ((invoice.getPaymentTerms().getOffsetMonthDue() == 0
              && invoice.getPaymentTerms().getOverduePaymentDaysRule() == 0)) {
            if (invoice.getTransactionDocument().isEeiComercialInv()) {

              plazo.appendChild(doc.createTextNode(String.valueOf(0)));
              pago2.appendChild(plazo);

              Element unidadTiempo = doc.createElement("unidadTiempo");

              strUnitTime = "dias";
              unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
              pago2.appendChild(unidadTiempo);
            }
          }

          pagos2.appendChild(pago2);
        }

      }

      if (!bgbGranTotalInvoice.equals(bgdGrandTotalPayment)) {

        if (!strPaymentMethod.equals(invoice.getPaymentMethod().getId())) {
          infoFactura.appendChild(pagos);
        } else {
          infoFactura.appendChild(pagos2);
        }
      } else if (bgbGranTotalInvoice.equals(bgdGrandTotalPayment)) {
        infoFactura.appendChild(pagos);

      }

    } else {

      Element pagos = doc.createElement("pagos");

      Element pago = doc.createElement("pago");

      Element formaPago = doc.createElement("formaPago");
      String strCodePaymentMethod = invoice.getPaymentMethod().getEeiCodeEi() == null ? "ND"
          : invoice.getPaymentMethod().getEeiCodeEi();
      if (strCodePaymentMethod.equals("ND")) {
        throw new OBException("El método de Pago " + invoice.getPaymentMethod().getName()
            + " no esta configurado para el proceso de Facturación Electrónica");

      }
      formaPago.appendChild(doc.createTextNode(strCodePaymentMethod));
      pago.appendChild(formaPago);

      Element total = doc.createElement("total");
      double dblpagos = invoice.getGrandTotalAmount().doubleValue();
      total.appendChild(doc.createTextNode(formateador.format(dblpagos).toString()));
      pago.appendChild(total);

      Element plazo = doc.createElement("plazo");

      String strUnitTime = "";
      if (invoice.getPaymentTerms().getOverduePaymentDaysRule() > 0) {

        double dblplazo = invoice.getPaymentTerms().getOverduePaymentDaysRule();
        plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
        pago.appendChild(plazo);

        Element unidadTiempo = doc.createElement("unidadTiempo");
        strUnitTime = "dias";
        unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
        pago.appendChild(unidadTiempo);
      } else if (invoice.getPaymentTerms().getOffsetMonthDue() > 0) {

        double dblplazo = invoice.getPaymentTerms().getOffsetMonthDue();
        plazo.appendChild(doc.createTextNode(String.valueOf(dblplazo)));
        pago.appendChild(plazo);

        Element unidadTiempo = doc.createElement("unidadTiempo");

        strUnitTime = "meses";
        unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
        pago.appendChild(unidadTiempo);

      } else if ((invoice.getPaymentTerms().getOffsetMonthDue() == 0 
      && invoice.getPaymentTerms().getOverduePaymentDaysRule() == 0)) {
      if(invoice.getTransactionDocument().isEeiComercialInv()) {
        
        plazo.appendChild(doc.createTextNode(String.valueOf(0)));
            pago.appendChild(plazo);

            Element unidadTiempo = doc.createElement("unidadTiempo");

            strUnitTime = "dias";
            unidadTiempo.appendChild(doc.createTextNode(strUnitTime));
            pago.appendChild(unidadTiempo);
      }
    }

      pagos.appendChild(pago);

      infoFactura.appendChild(pagos);

    }

    /** FIN FORMAS DE PAGOS - CC **/

    // *************************TERMINA PAGOS

    // detalles elements
    Element detalles = doc.createElement("detalles");
    rootElement.appendChild(detalles);

    Element detalle;
    Element codigoPrincipal;
    Element codigoAuxiliar;
    Element descripcion;
    Element cantidad;
    Element precioUnitario;
    Element descuento;
    Element precioTotalSinImpuesto;
    Element detallesAdicionales;
    Element detAdicional1;
    Element detAdicional2;
    Element detAdicional3;
    Element impuestos;
    Element impuesto;
    Element codigoImpuesto;
    Element codigoPorcentajeImpuesto;
    Element tarifaImpuesto;
    Element valorImpuesto;
    Element baseImponibleImpuesto;

    // chequear si lineas negativas
    List<InvoiceLine> negativesLines = hasInvoiceLinesWithNegativeAmounts(
        invoice.getInvoiceLineList(), new BigDecimal(TotalSinImpuestos));

    List<InvoiceLine> lines = invoice.getInvoiceLineList();

    if (negativesLines != null)
      lines = negativesLines;

    // ORDENAR POR NÚMERO DE LÍNEA
    Collections.sort(lines, new Comparator<InvoiceLine>() {
      @Override
      public int compare(InvoiceLine o1, InvoiceLine o2) {
        // TODO Auto-generated method stub
        return o1.getLineNo().compareTo(o2.getLineNo());
      }
    });

    List<List<String>> lstAttributes = null;
    try {
    	lstAttributes = getAttributes(invoice);   
    }catch(Exception e) {
    	throw new OBException("Error al consultar información de atributos. " + e.getMessage());
    }

    // TICKET 2963 A.M. 12/06/2018
    OBCriteria<EEIParamFacturae> ObjEeiParam = OBDal.getInstance()
        .createCriteria(EEIParamFacturae.class);
    ObjEeiParam.add(Restrictions.eq(EEIParamFacturae.PROPERTY_ACTIVE, true));

    EEIParamFacturae ObjParams = null;
    ObjParams = OBDal.getInstance().get(EEIParamFacturae.class, ObjEeiParam.list().get(0).getId());

    DecimalFormat formateador6 = new DecimalFormat("#########0.000000", simbolos);

    for (InvoiceLine detalleObj : lines) {
      detalle = doc.createElement("detalle");
      detalles.appendChild(detalle);

      if (ObjParams.isShowprincipalcode()) {
        codigoPrincipal = doc.createElement("codigoPrincipal");
        String strCodigoPrincipal = null;
        strCodigoPrincipal = truncate(detalleObj.getProduct().getSearchKey(), 25);
        if (strCodigoPrincipal == null || strCodigoPrincipal.trim().equals("")) {
          strCodigoPrincipal = "-";
        }

        codigoPrincipal.appendChild(doc.createTextNode(strCodigoPrincipal));
        detalle.appendChild(codigoPrincipal);
      }

      if (ObjParams.isShowauxiliarycode()) {
        codigoAuxiliar = doc.createElement("codigoAuxiliar");
        String strCodigoAuxiliar = null;
        strCodigoAuxiliar = truncate(detalleObj.getProduct().getEeiAlternativeidentifier(), 25);

        if (strCodigoAuxiliar == null || strCodigoAuxiliar.trim().equals("")) {
          strCodigoAuxiliar = truncate(detalleObj.getProduct().getSearchKey(), 25);
        }
        if (strCodigoAuxiliar == null || strCodigoAuxiliar.trim().equals("")) {
          strCodigoAuxiliar = "-";
        }
        codigoAuxiliar.appendChild(doc.createTextNode(strCodigoAuxiliar));
        detalle.appendChild(codigoAuxiliar);
      }

      String strDescription = "";
      if (ObjParams.getProductName().equals("N")) {
    	  strDescription = detalleObj.getProduct().getName();
      }else if (ObjParams.getProductName().equals("D")){
    	  strDescription = detalleObj.getProduct().getDescription();
      }else if (ObjParams.getProductName().equals("ND")){
    	  strDescription = detalleObj.getProduct().getName()+
    			  (detalleObj.getProduct().getDescription()==null?"":" - "+ detalleObj.getProduct().getDescription());
      }
      
      strDescription = truncate(strDescription, 300);
      if (strDescription == null || strDescription.trim().equals("")) {
        throw new OBException(
            "El producto (" + detalleObj.getProduct().getName() + ") no tiene descripción o nombre.");
      }
      strDescription = strDescription.replaceAll("[\n]", " ");
      descripcion = doc.createElement("descripcion");
      descripcion.appendChild(doc.createTextNode(strDescription));
      detalle.appendChild(descripcion);

      double Cantidad = detalleObj.getInvoicedQuantity().doubleValue();
      String strCantidad = "", strPrecioUnitario = "";
      if (strXmlVersion.equals("1.1.0")) {
        strCantidad = formateador6.format(Cantidad);
      } else {
        strCantidad = formateador.format(Cantidad);
      }
      cantidad = doc.createElement("cantidad");
      cantidad.appendChild(doc.createTextNode(strCantidad));
      detalle.appendChild(cantidad);

      double PrecioUnitario = detalleObj.getUnitPrice().doubleValue();

      double PrecioTotalSinImpuesto = detalleObj.getLineNetAmount().doubleValue();

      double DescuentoLinea = 0;

      if (boolDiscountbypricelist) {

        double PrecioTarifa = detalleObj.getListPrice().doubleValue();
        DescuentoLinea = 0;
        if (PrecioTarifa > 0 & PrecioTarifa > PrecioUnitario) {
          PrecioUnitario = PrecioTarifa;
          DescuentoLinea = Double.parseDouble(
              formateador.format(Cantidad * PrecioUnitario).toString()) - PrecioTotalSinImpuesto;
        }

      } else {

        double subtotalInicialLinea = Double
            .parseDouble(formateador.format(detalleObj.getSseedInitialSubtotal()).toString());
        double subtotalFinal = 0;
        subtotalFinal = Double
            .parseDouble(formateador.format(detalleObj.getLineNetAmount()).toString());
        if (subtotalInicialLinea > subtotalFinal) {
          PrecioUnitario = detalleObj.getSseedInitialunitprice().doubleValue();
          DescuentoLinea = subtotalInicialLinea - subtotalFinal;
        }

      }

      if (DescuentoLinea < 0) {
        DescuentoLinea = 0;
        PrecioUnitario = detalleObj.getUnitPrice().doubleValue();
      }

      if (strXmlVersion.equals("1.1.0")) {
        strPrecioUnitario = formateador6.format(PrecioUnitario);
      } else {
        strPrecioUnitario = formateador.format(PrecioUnitario);
      }

      precioUnitario = doc.createElement("precioUnitario");
      precioUnitario.appendChild(doc.createTextNode(strPrecioUnitario));
      detalle.appendChild(precioUnitario);

      descuento = doc.createElement("descuento");
      descuento.appendChild(doc.createTextNode(formateador.format(DescuentoLinea).toString()));
      detalle.appendChild(descuento);

      precioTotalSinImpuesto = doc.createElement("precioTotalSinImpuesto");
      precioTotalSinImpuesto.appendChild(doc.createTextNode(formateador.format(
          PrecioTotalSinImpuesto).toString()));
      detalle.appendChild(precioTotalSinImpuesto);

      //ATRIBUTOS
      
      if(lstAttributes !=null) {
	      int intCountAttr=0;
	      detallesAdicionales = doc.createElement("detallesAdicionales");
	      String strValorFinal = ""; 
	      for (int i=0; i<lstAttributes.get(0).size(); i++) {
	    	  
	    	  String strOrderId = lstAttributes.get(0).get(i);
	    	  
	    	  if(strOrderId !=null && !strOrderId.equals("") && strOrderId.equals(detalleObj.getId())) {
	
	    		  String strNombre = truncate(lstAttributes.get(1).get(i),300);
	        	  String strValor = truncate(lstAttributes.get(2).get(i),300);
	        	  
	        	  if (strNombre != null && strValor != null) {
	        		  strValorFinal= strValorFinal+strNombre+": "+strValor+"|";
	        		  intCountAttr++;      	      
	        	  }
	        	  
	    	  }
	      }
	      
	      if(strValorFinal!=null && !strValorFinal.equals("")) {

	    	  String strLinea1 = null;
              String strLinea2 = null;
              String strLinea3 = null;

              if(strValorFinal.length()<=300){
            	  strLinea1 = strValorFinal.substring(0,strValorFinal.length());
              }            
	    	  if(strValorFinal.length()>300 && strValorFinal.length()<=600){
	    		  strLinea1 = strValorFinal.substring(0,300);
	    		  strLinea2 = strValorFinal.substring(300,strValorFinal.length());
              }
              if(strValorFinal.length()>600 && strValorFinal.length()<=900){
            	  strLinea1 = strValorFinal.substring(0,300);
            	  strLinea2 = strValorFinal.substring(300,600);
            	  strLinea3 = strValorFinal.substring(600,strValorFinal.length());
              }
              if(strValorFinal.length()>900){
            	  strLinea1 = strValorFinal.substring(0,300);
            	  strLinea2 = strValorFinal.substring(300,600);
            	  strLinea3 = strValorFinal.substring(600,900);
              }
	    	  
	    	  if(strLinea1!=null && !strLinea1.equals("")) {
			      detAdicional1 = doc.createElement("detAdicional");
			      detAdicional1.setAttribute("nombre","Atributos");
			      detAdicional1.setAttribute("valor", strLinea1.replaceAll("[\n]", " "));
			      detallesAdicionales.appendChild(detAdicional1);
	    	  }
	    	  
		      if(strLinea2!=null && !strLinea2.equals("")) {
			      detAdicional2 = doc.createElement("detAdicional");
			      detAdicional2.setAttribute("nombre","Atributos");
			      detAdicional2.setAttribute("valor", strLinea2.replaceAll("[\n]", " "));
			      detallesAdicionales.appendChild(detAdicional2);		    	  
		      }
		      
		      if(strLinea3!=null && !strLinea3.equals("")) {
			      detAdicional3 = doc.createElement("detAdicional");
			      detAdicional3.setAttribute("nombre","Atributos");
			      detAdicional3.setAttribute("valor", strLinea3.replaceAll("[\n]", " "));
			      detallesAdicionales.appendChild(detAdicional3);		    	  
		      }		      
	      }
	      
	      if (intCountAttr>0) {
	    	  detalle.appendChild(detallesAdicionales);
	      }
	      
      }

      // IMPUESTOS
      impuestos = doc.createElement("impuestos");
      detalle.appendChild(impuestos);
      
      // IRBP INFO
      BigDecimal indice = BigDecimal.ZERO;
      String codeTaxIrbp = "";
      double Tarifa = 0; // irbpTax.getRate().doubleValue();

      OBCriteria<TaxRate> obc = OBDal.getInstance().createCriteria(TaxRate.class);
      obc.add(Restrictions.eq(TaxRate.PROPERTY_SLPLAGIRBP, true));
      obc.setMaxResults(1);
      TaxRate irbpTax = (TaxRate) obc.uniqueResult();
      if (irbpTax != null) {
        indice = irbpTax.getRate();
        if (irbpTax.getEeiSriTaxcatCode() != null) {
          codeTaxIrbp = irbpTax.getEeiSriTaxcatCode().toString();
        }
        if (irbpTax.getRate() != null) {
          Tarifa = irbpTax.getRate().doubleValue();
        }
      }

      for (InvoiceLineTax detalleImpuestoObj : detalleObj.getInvoiceLineTaxList()) {
        impuesto = doc.createElement("impuesto");
        impuestos.appendChild(impuesto);

        /* CODIGO DEL IMPUESTO */
        codigoImpuesto = doc.createElement("codigo");

        if (detalleImpuestoObj.getTax().isTaxdeductable()
            && !detalleImpuestoObj.getTax().getRate().toString().equals("0"))// (detalleImpuestoObj.getTax().getName().toString().equals("IVA
          // 12% - 01-01-2011"))
          codigoImpuesto.appendChild(doc.createTextNode("2")); // validar
        else if (detalleImpuestoObj.getTax().isTaxdeductable()
            && detalleImpuestoObj.getTax().getRate().toString().equals("0"))// (detalleImpuestoObj.getTax().getName().toString().equals("IVA
          // 0% - 01-01-2011"))
          codigoImpuesto.appendChild(doc.createTextNode("2")); // validar
        else if(detalleImpuestoObj.getTax().isSlplagIrbp() )
          codigoImpuesto.appendChild(doc.createTextNode("5"));
        else
          codigoImpuesto.appendChild(doc.createTextNode("3")); // validar

        impuesto.appendChild(codigoImpuesto);
        /* FIN DE CODIGO DEL IMPUESTO */

        codigoPorcentajeImpuesto = doc.createElement("codigoPorcentaje");
        String codigoSriFeDet = ""; // tax.getTax().getEeiSriTaxcatCode().toString();
        if (detalleImpuestoObj.getTax() != null) {
          if (detalleImpuestoObj.getTax().getEeiSriTaxcatCode() == null) {
            String msg = "El impuesto " + detalleImpuestoObj.getTax().getName().toString()
                + " no tiene configurado el campo 'Código Impuesto SRI - FE'.";

            throw new OBException(msg);
          }
          if (detalleImpuestoObj.getTax().getEeiSriTaxcatCode() != null) {
            codigoSriFeDet = detalleImpuestoObj.getTax().getEeiSriTaxcatCode().toString();
          }
        }
        // if (detalleImpuestoObj.getTax().isTaxdeductable()
        // && !detalleImpuestoObj.getTax().getRate().toString().equals("0"))
        // codigoPorcentajeImpuesto.appendChild(
        // doc.createTextNode((detalleImpuestoObj.getTax().getEeiSriTaxcatCode() != null)
        // ? detalleImpuestoObj.getTax().getEeiSriTaxcatCode().toString()
        // : ""));
        // else if (detalleImpuestoObj.getTax().isTaxdeductable()
        // && detalleImpuestoObj.getTax().getRate().toString().equals("0"))
        // codigoPorcentajeImpuesto.appendChild(doc.createTextNode("0"));
        // else if (detalleImpuestoObj.getTax().isSlplagIrbp())
        // codigoPorcentajeImpuesto.appendChild(
        // doc.createTextNode(detalleImpuestoObj.getTax().getEeiSriTaxcatCode().toString()));

        // else
        // codigoPorcentajeImpuesto.appendChild(doc.createTextNode("6"));
        codigoPorcentajeImpuesto.appendChild(doc.createTextNode(codigoSriFeDet));
        impuesto.appendChild(codigoPorcentajeImpuesto);

        /* FIN DE CODIGO DEL IMPUESTO RATE */
        double TarifaImpuesto = detalleImpuestoObj.getTax().getRate().doubleValue();
        tarifaImpuesto = doc.createElement("tarifa");
        // tarifaImpuesto.appendChild(doc.createTextNode(detalleImpuestoObj.getTax().getRate().toPlainString()));
        // // validar
        if (detalleImpuestoObj.getTax().isSlplagIrbp() ){
          String numberString = Double.toString(Tarifa);
          char firsDigit = numberString.charAt(0);
          int myInt = Integer.parseInt(String.valueOf(firsDigit));
          tarifaImpuesto.appendChild(doc.createTextNode(formateador.format(myInt).toString())); // validar
          impuesto.appendChild(tarifaImpuesto);
        }else {
          tarifaImpuesto
          .appendChild(doc.createTextNode(formateador.format(TarifaImpuesto).toString())); // validar
          impuesto.appendChild(tarifaImpuesto);
        }


        double BaseImponibleImpuesto = detalleImpuestoObj.getTaxableAmount().doubleValue();
        baseImponibleImpuesto = doc.createElement("baseImponible");
        // baseImponibleImpuesto.appendChild(doc.createTextNode(detalleImpuestoObj.getTaxableAmount()
        // .toPlainString()));
        baseImponibleImpuesto
            .appendChild(doc.createTextNode(formateador.format(BaseImponibleImpuesto).toString()));
        impuesto.appendChild(baseImponibleImpuesto);

        double ValorImpuesto = detalleImpuestoObj.getTaxAmount().doubleValue();
        valorImpuesto = doc.createElement("valor");
        // valorImpuesto.appendChild(doc.createTextNode(detalleImpuestoObj.getTaxAmount()
        // .toPlainString()));
        valorImpuesto.appendChild(doc.createTextNode(formateador.format(ValorImpuesto).toString()));
        impuesto.appendChild(valorImpuesto);
      }

    }

    // *****************INICIA TAGS REEMBOLSOS
    if (invoice.isSsreIsrefund()) 
    {
        Element reembolsos = doc.createElement("reembolsos");
        rootElement.appendChild(reembolsos);

    	invoice.getEEIRRefundList();
    	
    	for (eeirRefund invoice_refund : invoice.getEEIRRefundList()) {
    	
         Element reembolsoDetalle = doc.createElement("reembolsoDetalle");
         reembolsos.appendChild(reembolsoDetalle);
    		
    	Invoice purchase_invoice = OBDal.getInstance().get(Invoice.class,
    			invoice_refund.getEeirInvoice().getId());
		
      // TIPO IDENTIFICACIÓN
      String strRefundTaxID = null;
      if (purchase_invoice.getBusinessPartner().getSswhTaxidtype().equals("R")) {
    	  strRefundTaxID = "04";
      } else if (purchase_invoice.getBusinessPartner().getSswhTaxidtype().equals("D")) {
    	  strRefundTaxID = "05";
      } else if (purchase_invoice.getBusinessPartner().getSswhTaxidtype().equals("P")) {
    	  strRefundTaxID = "06";
      } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("EEI_C")) {
    	  strRefundTaxID = "07";
      } else if (invoice.getBusinessPartner().getSswhTaxidtype().equals("EEI_E")) {    	
    	  strRefundTaxID = "08";
      }
      Element tipoIdentificacionProveedorReembolso = doc
          .createElement("tipoIdentificacionProveedorReembolso");
      tipoIdentificacionProveedorReembolso.appendChild(doc.createTextNode(strRefundTaxID));
      reembolsoDetalle.appendChild(tipoIdentificacionProveedorReembolso);

      // IDENTIFICACIÓN
      String strRefundTaxid = purchase_invoice.getBusinessPartner().getTaxID();
      if (strRefundTaxid == null || strRefundTaxid.trim().equals("")) {
        throw new OBException("El cliente no tiene identificación (CIF/NIF).");
      }
      Element identificacionProveedorReembolso = doc
          .createElement("identificacionProveedorReembolso");
      identificacionProveedorReembolso.appendChild(doc.createTextNode(strRefundTaxid));
      reembolsoDetalle.appendChild(identificacionProveedorReembolso);

      // CÓDIGO PAÍS
      Element codPaisPagoProveedorReembolso = doc.createElement("codPaisPagoProveedorReembolso");
      codPaisPagoProveedorReembolso.appendChild(doc.createTextNode("593"));

      reembolsoDetalle.appendChild(codPaisPagoProveedorReembolso);

      // TIPO DE PROVEEDOR REEMBOLSO
      String strTipoProveedorReembolso = purchase_invoice.getBusinessPartner().getSSWHTaxpayer()
          .getSearchKey();

      if (strTipoProveedorReembolso == null || strTipoProveedorReembolso.equals("")) {
        throw new OBException(
            "Identificador de tipo de contribuyente del tercero de la factura de compra referenciada (reembolso) no configurado.");
      }
      Element tipoProveedorReembolso = doc.createElement("tipoProveedorReembolso");
      tipoProveedorReembolso.appendChild(doc.createTextNode(strTipoProveedorReembolso));
      reembolsoDetalle.appendChild(tipoProveedorReembolso);

      // CÓDIGO DOCUMENTO REEMBOLSO
      //String strCodDocReembolso = purchase_invoice.getSswhLivelihood().getSearchKey();
      String strCodDocReembolso = invoice_refund.getSsccLivelihoodt().getSearchKey();
      if (strCodDocReembolso == null || strCodDocReembolso.equals("")) {
        throw new OBException(
            "Identificador del tipo de comprobante en datos de retención de la factura de compra referenciada (reembolso) no configurado.");
      }
      Element codDocReembolso = doc.createElement("codDocReembolso");
      codDocReembolso.appendChild(doc.createTextNode(strCodDocReembolso));
      reembolsoDetalle.appendChild(codDocReembolso);

      // *******NÚMERO DE FACTURA DE COMPRA
      String strDocRefund = purchase_invoice.getOrderReference();

      if (strDocRefund == null || strDocRefund.equals("")) {
        throw new OBException(
            "La factura de compra (reembolsos) no tiene asociada el Número de Retención");
      }

      String strRefundSubDocumentNo = strDocRefund.substring(8);
      while (strRefundSubDocumentNo.length() < 9) {
        strRefundSubDocumentNo = "0" + strRefundSubDocumentNo;
      }
      System.out.println("reembolso parte 2    " + strRefundSubDocumentNo);
      String strRefundSubDocumentNo1 = purchase_invoice.getOrderReference().substring(0, 8);
      System.out.println("reembolso parte 1   " + strRefundSubDocumentNo1);
      String strRefundDocumentnoX = strRefundSubDocumentNo1 + strRefundSubDocumentNo;
      String[] strRefdocumentno = null;
      System.out.println(strRefundDocumentnoX);
      if (documentnoX.matches("^\\d{3}-\\d{3}-\\d{9}$")) {
        strRefdocumentno = strRefundDocumentnoX.split("-");
      } else {
        throw new OBException(
            "El formato del número de documento de la factura de compra asociada (reembolso) es incorrecto (000-000-000000000).");
      }

      Element estabDocReembolso = doc.createElement("estabDocReembolso");
      estabDocReembolso.appendChild(doc.createTextNode(strRefdocumentno[0]));
      reembolsoDetalle.appendChild(estabDocReembolso);

      Element ptoEmiDocReembolso = doc.createElement("ptoEmiDocReembolso");
      ptoEmiDocReembolso.appendChild(doc.createTextNode(strRefdocumentno[1]));
      reembolsoDetalle.appendChild(ptoEmiDocReembolso);

      Element secuencialDocReembolso = doc.createElement("secuencialDocReembolso");
      secuencialDocReembolso.appendChild(doc.createTextNode(strRefdocumentno[2]));
      reembolsoDetalle.appendChild(secuencialDocReembolso);

      Element fechaEmisionDocReembolso = doc.createElement("fechaEmisionDocReembolso");
      fechaEmisionDocReembolso
          .appendChild(doc.createTextNode(ecFormat.format(purchase_invoice.getInvoiceDate())));
      reembolsoDetalle.appendChild(fechaEmisionDocReembolso);

      String strRefAuthNumber = purchase_invoice.getSswhNroauthorization();
      if (strRefAuthNumber == null || strRefAuthNumber.equals("")) {
        throw new OBException(
            "El número de autorización de la factura de compra referenciada (reembolso) es nulo. ");
      }

      Element numeroautorizacionDocReemb = doc.createElement("numeroautorizacionDocReemb");
      numeroautorizacionDocReemb.appendChild(doc.createTextNode(strRefAuthNumber));
      reembolsoDetalle.appendChild(numeroautorizacionDocReemb);

      Element detalleImpuestos = doc.createElement("detalleImpuestos");
      reembolsoDetalle.appendChild(detalleImpuestos);

      for (InvoiceTax impuestoObj : purchase_invoice.getInvoiceTaxList()) {
        if (impuestoObj.getTax().isTaxdeductable()) {
          Element detalleImpuesto = doc.createElement("detalleImpuesto");
          detalleImpuestos.appendChild(detalleImpuesto);

          Element codigo = doc.createElement("codigo");

          if (impuestoObj.getTax().isTaxdeductable()
              && !impuestoObj.getTax().getRate().toString().equals("0")) {// (impuestoObj.getTax().getName().toString().equals("IVA
                                                                          // 12% - 01-01-2011"))
            codigo.appendChild(doc.createTextNode("2"));
          } else if (impuestoObj.getTax().isTaxdeductable()
              && impuestoObj.getTax().getRate().toString().equals("0")) {// (impuestoObj.getTax().getName().toString().equals("IVA
                                                                         // 0% - 01-01-2011"))
            codigo.appendChild(doc.createTextNode("2"));
          } else {
            codigo.appendChild(doc.createTextNode("3"));

          }
          detalleImpuesto.appendChild(codigo);
          /* FIN DE CODIGO DEL IMPUESTO */

          /* INICIO CODIGO DEL IMPUESTO RATE */
          Element codigoPorcentaje = doc.createElement("codigoPorcentaje");
          if (impuestoObj.getTax().isTaxdeductable()
              && !impuestoObj.getTax().getRate().toString().equals("0")) {// (impuestoObj.getTax().getName().toString().equals("IVA
                                                                          // 12% - 01-01-2011"))
            codigoPorcentaje
                .appendChild(doc.createTextNode((impuestoObj.getTax().getEeiSriTaxcatCode() != null)
                    ? impuestoObj.getTax().getEeiSriTaxcatCode().toString()
                    : ""));
          } else if (impuestoObj.getTax().isTaxdeductable()
              && impuestoObj.getTax().getRate().toString().equals("0")) {// (impuestoObj.getTax().getName().toString().equals("IVA
                                                                         // 0% - 01-01-2011"))
            codigoPorcentaje.appendChild(doc.createTextNode("0"));
          } else {
            codigoPorcentaje.appendChild(doc.createTextNode("6"));
          }
          detalleImpuesto.appendChild(codigoPorcentaje);

          /* FIN DE CODIGO DEL IMPUESTO RATE */

          double Tarifa = impuestoObj.getTax().getRate().doubleValue();
          int intRate = (int) Tarifa;
          Element tarifa = doc.createElement("tarifa");
          tarifa.appendChild(doc.createTextNode(String.valueOf(intRate)));
          detalleImpuesto.appendChild(tarifa);

          double BaseImp = impuestoObj.getTaxableAmount().doubleValue();
          Element baseImponibleReembolso = doc.createElement("baseImponibleReembolso");
          baseImponibleReembolso
              .appendChild(doc.createTextNode(formateador.format(BaseImp).toString()));
          detalleImpuesto.appendChild(baseImponibleReembolso);

          double dbimpuestoReembolso = impuestoObj.getTaxAmount().doubleValue();
          Element impuestoReembolso = doc.createElement("impuestoReembolso");
          impuestoReembolso
              .appendChild(doc.createTextNode(formateador.format(dbimpuestoReembolso).toString()));
          detalleImpuesto.appendChild(impuestoReembolso);
        }
      }
     }
   }

    // ****************FIN TAGS REEMBOLSOS

    // **** FACTURAS NEGOCIABLES CAMPO DIRECCIONCOMPRADOR
    if (invoice.getTransactionDocument().isEeiComercialInv()) {
    	
    	if(invoice.getBusinessPartner().getEEIEmail() != null) {
    		Element tipoNegociable = doc.createElement("tipoNegociable");
    		Element correoNegociable = doc.createElement("correo");
    	
    		correoNegociable.appendChild(doc.createTextNode(invoice.getBusinessPartner().getEEIEmail()));
        	tipoNegociable.appendChild(correoNegociable);
        	rootElement.appendChild(tipoNegociable);
    	} else {
    		 throw new OBException("El tercero no cuenta con un correo electronico en la sección: Facturación electrónica.");
    	}
    	
    }

    // INFOADICIONAL
    OBCriteria<FIN_PaymentSchedule> objPaymentPlan = OBDal.getInstance()
            .createCriteria(FIN_PaymentSchedule.class);
    objPaymentPlan.add(Restrictions.eq(FIN_PaymentSchedule.PROPERTY_INVOICE, invoice));
    objPaymentPlan.setProjection(Projections.max(FIN_PaymentSchedule.PROPERTY_DUEDATE));
    objPaymentPlan.setFirstResult(1);
    
    String fecha_vencimiento="";

    try{
    
    fecha_vencimiento = ecFormat.format(
      objPaymentPlan.list().size()>0 ? 
      objPaymentPlan.list().get(0).getDueDate():null    
    );
    }catch(Exception efe){
      
    }
    

    if (ObjParams.isSendInfoSpecial()){
        String dataInvoice[] = new String[6];
        dataInvoice = ClientSOAP.getDataInv(invoice.getId(), invoice, null);      

        Element infoAdicional = doc.createElement("infoAdicional");
        rootElement.appendChild(infoAdicional);

        if (dataInvoice[0] != null) {
            Element campoAdicionaldir = doc.createElement("campoAdicional");
            campoAdicionaldir.setAttribute("nombre", "Dirección");
            campoAdicionaldir.appendChild(doc.createTextNode(dataInvoice[0]));
            infoAdicional.appendChild(campoAdicionaldir);
        }      
        if (dataInvoice[1] != null) {
            Element campoAdicionaltel = doc.createElement("campoAdicional");
            campoAdicionaltel.setAttribute("nombre", "Teléfono");
            campoAdicionaltel.appendChild(doc.createTextNode(dataInvoice[1]));
            infoAdicional.appendChild(campoAdicionaltel);
        }
        if (dataInvoice[2] != null) {
            Element campoAdicionalem = doc.createElement("campoAdicional");
            campoAdicionalem.setAttribute("nombre", "E-mail");
            campoAdicionalem.appendChild(doc.createTextNode(dataInvoice[2]));
            infoAdicional.appendChild(campoAdicionalem);
        }
        
        if(StringUtils.isNotEmpty(fecha_vencimiento)) {
            Element campoAdicionalfn = doc.createElement("campoAdicional");
            campoAdicionalfn.setAttribute("nombre", "FechaVencimiento");
            campoAdicionalfn.appendChild(doc.createTextNode(fecha_vencimiento));
            infoAdicional.appendChild(campoAdicionalfn);
        }

        if(StringUtils.isNotEmpty(invoice.getDescription()) || StringUtils.isNotEmpty(invoice.getEeiDescription())) {
        	String descrip = (invoice.getDescription()!=null?invoice.getDescription():"")+" "+(invoice.getEeiDescription()!=null?invoice.getEeiDescription():"");
	        Element campoAdicional1 = doc.createElement("campoAdicional");
	        campoAdicional1.setAttribute("nombre", "Adicional1");
	        campoAdicional1.appendChild(doc.createTextNode(truncate(descrip, 300)));
	        infoAdicional.appendChild(campoAdicional1);
        }

        if(StringUtils.isNotEmpty(ObjParams.getAdittionalInfo())) {
	        Element campoAdicional2 = doc.createElement("campoAdicional");
	        campoAdicional2.setAttribute("nombre", "Adicional2");
	        campoAdicional2.appendChild(doc.createTextNode(truncate(ObjParams.getAdittionalInfo(), 300)));
	        infoAdicional.appendChild(campoAdicional2);        
        }
        
        String ciudad = invoice.getPartnerAddress().getLocationAddress().getCityName();
        String proceso = "", emailsolicita="", ordencompra="";
        if (invoice.getSalesOrder() != null) {
        	proceso = invoice.getSalesOrder().getDocumentNo();
        	emailsolicita = getEmailSolicitante(invoice);
        	ordencompra = invoice.getSalesOrder().getOrderReference()!=null?invoice.getSalesOrder().getOrderReference():""; 
        }
        String metodopago = invoice.getPaymentMethod().getName();
        String condicionpago = invoice.getPaymentTerms().getName();
        
        //CUENTAS BANCARIAS
        OBCriteria<EeiBankAccount> obBankAccount = OBDal.getInstance()
                .createCriteria(EeiBankAccount.class);
        
        String strBank = "", strAccount="";
        List<EeiBankAccount> lstBank = obBankAccount.list();
        
        for(int i=0; i<lstBank.size(); i++) {
        	if(i!=0) {
        		strBank = strBank+"++";
        		strAccount = strAccount+"++";
        	}
        	strBank = strBank+lstBank.get(i).getName();
        	strAccount = strAccount+lstBank.get(i).getAccount();
        }
        
        String strFinal = ciudad+";;"+proceso+";;"+metodopago+";;"+condicionpago+";;"+fecha_vencimiento+";;"+ordencompra
        		+";;"+emailsolicita+";;"+strBank+";;"+strAccount;

        Element campoAdicional3 = doc.createElement("campoAdicional");
        campoAdicional3.setAttribute("nombre", "Adicional3");
        campoAdicional3.appendChild(doc.createTextNode(truncate(strFinal, 300)));
        infoAdicional.appendChild(campoAdicional3);                  

    } else{
      String dataInvoice[] = new String[6];
      dataInvoice = ClientSOAP.getDataInv(invoice.getId(), invoice, null);

      if ((((invoice.getDescription() != null && !invoice.getDescription().trim().equals(""))
          || (invoice.getEeiDescription() != null && !invoice.getEeiDescription().trim().equals("")))
          && !invoice.getDocumentType().getEeiDescriptionfields().equals("NO"))
          || (invoice.getBusinessPartner().getName2() != null)
          || (ObjParams.getAdittionalInfo() != null && !ObjParams.getAdittionalInfo().trim().equals(""))
          || (dataInvoice[0] != null || dataInvoice[1] != null || dataInvoice[2] != null)
          || (StringUtils.isNotEmpty(fecha_vencimiento))) {

        Element infoAdicional = doc.createElement("infoAdicional");
        rootElement.appendChild(infoAdicional);
        
        if (dataInvoice[0] != null) {
            Element campoAdicionaldir = doc.createElement("campoAdicional");
            campoAdicionaldir.setAttribute("nombre", "Dirección");
            campoAdicionaldir.appendChild(doc.createTextNode(dataInvoice[0]));
            infoAdicional.appendChild(campoAdicionaldir);
        }      
        if (dataInvoice[1] != null) {
            Element campoAdicionaltel = doc.createElement("campoAdicional");
            campoAdicionaltel.setAttribute("nombre", "Teléfono");
            campoAdicionaltel.appendChild(doc.createTextNode(dataInvoice[1]));
            infoAdicional.appendChild(campoAdicionaltel);
        }
        if (dataInvoice[2] != null) {
            Element campoAdicionalem = doc.createElement("campoAdicional");
            campoAdicionalem.setAttribute("nombre", "E-mail");
            campoAdicionalem.appendChild(doc.createTextNode(dataInvoice[2]));
            infoAdicional.appendChild(campoAdicionalem);
        }      
        
        if(StringUtils.isNotEmpty(fecha_vencimiento)) {
            Element campoAdicionalfn = doc.createElement("campoAdicional");
            campoAdicionalfn.setAttribute("nombre", "FechaVencimiento");
            campoAdicionalfn.appendChild(doc.createTextNode(fecha_vencimiento));
            infoAdicional.appendChild(campoAdicionalfn);
        }
        
        String StrUnionCadenaSinSaltos = "";
        
        /*if(ObjParams.getAdittionalInfo() != null && !ObjParams.getAdittionalInfo().trim().equals("")) {
            Element campoAdicional = doc.createElement("campoAdicional");
            campoAdicional.setAttribute("nombre", "Descripción");
            campoAdicional.appendChild(doc.createTextNode(truncate(ObjParams.getAdittionalInfo(),300)));
            infoAdicional.appendChild(campoAdicional);
            StrUnionCadenaSinSaltos=";";
        }  */    
        
        if (invoice.getBusinessPartner().getName2() != null
            || invoice.getBusinessPartner().getDescription() != null) {

          String strDescription3 = invoice.getBusinessPartner().getDescription() == null ? "ND"
              : invoice.getBusinessPartner().getDescription();
          String strNam2 = invoice.getBusinessPartner().getName2() == null ? "ND"
              : invoice.getBusinessPartner().getName2();
          String strName3 = strExtract && !strDescription3.equals("ND") ? strDescription3 : strNam2;

          if (!strName3.equals("ND")) {
            Element campoAdicional3 = doc.createElement("campoAdicional");
            campoAdicional3.setAttribute("nombre", "NombreComercialTercero");
            campoAdicional3.appendChild(
                doc.createTextNode(truncate(invoice.getBusinessPartner().getName2(), 300)));
            infoAdicional.appendChild(campoAdicional3);
          }
        }

        if ((((invoice.getDescription() != null && !invoice.getDescription().trim().equals(""))
            || (invoice.getEeiDescription() != null && !invoice.getEeiDescription().trim().equals("")))
            && !invoice.getDocumentType().getEeiDescriptionfields().equals("NO"))) {
          
              
          
          if(((invoice.getDescription() != null && !invoice.getDescription().trim().equals(""))
                  || (invoice.getEeiDescription() != null && !invoice.getEeiDescription().trim().equals("")))
                  && !invoice.getDocumentType().getEeiDescriptionfields().equals("NO")) {
            
            if (invoice.getDocumentType().getEeiDescriptionfields().equals("DEDA")) {
    
              StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos+(invoice.getDescription() == null ? ""
                  : invoice.getDescription()) + ";"
                  + (invoice.getEeiDescription() == null ? "" : invoice.getEeiDescription());
    
            } else if (invoice.getDocumentType().getEeiDescriptionfields().equals("DE")) {
    
              StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos+(invoice.getDescription() == null ? ""
                  : invoice.getDescription());
    
            } else if (invoice.getDocumentType().getEeiDescriptionfields().equals("DA")) {
    
              StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos+(invoice.getEeiDescription() == null ? ""
                  : invoice.getEeiDescription());
    
            }
            
          }  
          
          if (ObjParams.getAdittionalInfo() != null && !ObjParams.getAdittionalInfo().trim().equals("")) {
            StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos+ObjParams.getAdittionalInfo();
          }  

          StrUnionCadenaSinSaltos = String.valueOf(StrUnionCadenaSinSaltos).replaceAll("[\n]", ";");

          StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos.replaceAll(";;;", ";");
          StrUnionCadenaSinSaltos = StrUnionCadenaSinSaltos.replaceAll(";;", ";");

          if(StrUnionCadenaSinSaltos.equals(";")) {
            StrUnionCadenaSinSaltos="";
          }
          String strCadenaParcial = "";
          String strCadenaConcatenada = "";
          int intContador = 0;
          int j = 0;
          for (int i = 0; i < StrUnionCadenaSinSaltos.length(); i = i + 300) {
            j = i + 300;
            if (j > StrUnionCadenaSinSaltos.length()) {
              break;
            }
            strCadenaParcial = StrUnionCadenaSinSaltos.substring(i, j);
            strCadenaConcatenada = strCadenaConcatenada + strCadenaParcial;
            intContador = intContador + 1;
            Element campoAdicional2 = doc.createElement("campoAdicional");
            campoAdicional2.setAttribute("nombre", "Descripción" + intContador);
            campoAdicional2.appendChild(doc.createTextNode(truncate(strCadenaParcial, 300)));
            infoAdicional.appendChild(campoAdicional2);
          }
          if (strCadenaConcatenada.length() < StrUnionCadenaSinSaltos.length()) {
            intContador = intContador + 1;
            strCadenaParcial = StrUnionCadenaSinSaltos.substring(strCadenaConcatenada.length(),
                StrUnionCadenaSinSaltos.length());
            Element campoAdicional2 = doc.createElement("campoAdicional");
            campoAdicional2.setAttribute("nombre", "Descripción" + intContador);
            campoAdicional2.appendChild(doc.createTextNode(truncate(strCadenaParcial, 300)));
            infoAdicional.appendChild(campoAdicional2);
          }

        }
      }
    }

    String strFile = doc.toString();

    DOMSource domSource = new DOMSource(doc);
    StringWriter writer = new StringWriter();
    StreamResult result = new StreamResult(writer);
    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer transformer = tf.newTransformer();
    transformer.transform(domSource, result);

    return writer.toString();
  }

  public boolean validateFile(File file) throws Exception {
    URL schemaFile = new URL("http://cheli.aradaen.com/factura.xsd");
    Source xmlFile = new StreamSource(file);
    // SchemaFactory schemaFactory =
    // SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
    SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.XMLNS_ATTRIBUTE_NS_URI);
    Schema schema = schemaFactory.newSchema(schemaFile);
    Validator validator = schema.newValidator();
    try {
      validator.validate(xmlFile);
    } catch (SAXException e) {
      return false;
    }
    return true;
  }

  public static String truncate(String value, int length) {

    if (value == null || value.equals("")) {
      return null;
    } else {
      if (value.length() > length) {
        return value.substring(0, length);
      } else {
        return value;
      }
    }
  }

  @Override
  public String sendFile(ConnectionProvider con, File file, Invoice invoice, String strLanguage)
      throws Exception {

    ECWSClient client = new ECWSClient();
    String res = null;// client.send(con, file, invoice, strLanguage);

    return res;
  }

  @Override
  public String getFTPFolderName() {
    return "Factura31";
  }

  @Override
  public String generateFile(Set<Invoice> invoices, String tmpDir, String lang) throws Exception {
    // TODO Auto-generated method stub
    return null;
  }

  public static String SelectFirstLine(String strInvoiceId) {
    ConnectionProvider conn = new DalConnectionProvider(false);

    try {
      String strSql = "SELECT IL.C_INVOICELINE_ID  FROM C_INVOICELINE IL WHERE IL.C_INVOICE_ID =? AND IL.LINE="
          + "(SELECT MIN(IL2.LINE) FROM C_INVOICELINE IL2 WHERE IL2.C_INVOICE_ID =?) "
          + "ORDER BY IL.CREATED ASC";
      PreparedStatement st = null;
      String strParametro = null;
      st = conn.getPreparedStatement(strSql);
      st.setString(1, strInvoiceId);
      st.setString(2, strInvoiceId);
      ResultSet rsConsulta = st.executeQuery();
      int contador = 0;
      while (rsConsulta.next()) {
        contador = contador + 1;
        if (contador == 1) {
          strParametro = rsConsulta.getString("C_INVOICELINE_ID");
          break;
        }
      }
      return strParametro;
    } catch (Exception e) {

      throw new OBException("Error al consultar la tabla c_invoiceline (Referencia Albarán) " + e);
    } finally {
      try {
        conn.destroy();
      } catch (Exception e) {

      }
    }

  }

  public static String getRefundData(Invoice invOb, String strCodigoRetorno) {
    ConnectionProvider conn = new DalConnectionProvider(false);
    String strResult = null;
    try {

      String strSql = "SELECT eei_get_refund_values(?,?) AS RESULTADO  FROM  DUAL ";
      PreparedStatement st = null;

      st = conn.getPreparedStatement(strSql);
      st.setString(1, invOb.getId());
      st.setString(2, strCodigoRetorno);
      ResultSet rsConsulta = st.executeQuery();

      while (rsConsulta.next()) {
        strResult = rsConsulta.getString("RESULTADO");
      }

      return strResult;

    } catch (Exception e) {

      throw new OBException("Error al consultar información de reembolsos. " + e.getMessage());
    } finally {
      try {
        conn.destroy();
      } catch (Exception e) {

      }
    }

  }
  
  public static List<List<String>> getAttributes(Invoice invOb) {
	  
	    ConnectionProvider conn = new DalConnectionProvider(false);
	    String strSQLAttributes = null;
	    try {

	      String strSql = "SELECT sql_attributes FROM eei_param_facturae where isactive='Y'";
	      PreparedStatement st = null;

	      st = conn.getPreparedStatement(strSql);
	      ResultSet rsConsulta = st.executeQuery();

	      while (rsConsulta.next()) {
	    	  strSQLAttributes = rsConsulta.getString("sql_attributes");
	      }
	      
	      st = conn.getPreparedStatement(strSQLAttributes);
	      st.setString(1, invOb.getId());
	      ResultSet rsAttributes = st.executeQuery();

          List<String> arrInvoiceLine = new ArrayList<String>();
          List<String> arrNombre = new ArrayList<String>();
          List<String> arrValor = new ArrayList<String>();
          
          List<List<String>> arrResult = new ArrayList<List<String>>();
          
	      while (rsAttributes.next()) {
	    	  arrInvoiceLine.add(rsAttributes.getString("c_invoiceline_id"));
	    	  arrNombre.add(rsAttributes.getString("nombre"));
	    	  arrValor.add(rsAttributes.getString("valor"));
	      }
	      
	      arrResult.add(arrInvoiceLine);
	      arrResult.add(arrNombre);
	      arrResult.add(arrValor);
      
	      return arrResult;

	    } catch (Exception e) {
	    	throw new OBException("Error al consultar información de atributos. " + e.getMessage());
	    } finally {
	      try {
	        conn.destroy();
	      } catch (Exception e) {

	      }
	    }

  }
  
  public static String getEmailSolicitante(Invoice invOb) {
	    ConnectionProvider conn = new DalConnectionProvider(false);
	    String strResult = "";
	    try {

	      String strSql = "SELECT EM_Scactu_Emailrequest AS RESULTADO  FROM  c_order WHERE EM_Scactu_Emailrequest IS NOT NULL AND c_order_id=? ";
	      PreparedStatement st = null;

	      st = conn.getPreparedStatement(strSql);
	      st.setString(1, invOb.getSalesOrder().getId());
	      ResultSet rsConsulta = st.executeQuery();

	      while (rsConsulta.next()) {
	        strResult = rsConsulta.getString("RESULTADO");
	      }

	      return strResult;

	    } catch (Exception e) {

	      return "";
	    } finally {
	      try {
	        conn.destroy();
	      } catch (Exception e) {

	      }
	    }

	  }  
}
