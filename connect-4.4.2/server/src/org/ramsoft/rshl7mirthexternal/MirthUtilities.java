package org.ramsoft.rshl7mirthexternal;

public class MirthUtilities {
	/**
	 * @param args
	 */
	public native String GetDatabaseLocation();	
	public native String GetPRDLLPath();	
	public native String DecryptString(String EncryptedStr);	
	public native String EncryptString(String DecryptedStr);	

	public native void Initialize(String LogFileName, String PRServerLogFileName, int MaxLogFiles);
	public native void Finalize();
	public native void Test();
	public native void TestArrayOutput(int[] OperationStatus);
	
	public native byte[] GetReportContent(String SOPInstanceUID, int DocumentType, int[] OperationStatus, 
		long[] ObservationDateTime, int[] ObservationTimeZone, String[] ObserverName, String[] ObserverID, String[] ObserverOrganization);
	
	public native int SetReportContent(String StudyUID, int ContentType, int DocumentTypeID, String DocumentTitle, String TemplateURL, String ObserverName,
		String ObserverID, String ObserverOrganization, long JavaObservationDateTime, int ObservationTimeZone, String AuditTrailAccessType, boolean DistributeReport, byte[] ReportContentBuffer, String UserName);
	
	public native int SetReportContentWithSOPInstanceUID(String StudyUID, int ContentType, int DocumentTypeID, String DocumentTitle, String TemplateURL, 	  String ObserverName,
		String ObserverID, String ObserverOrganization, long JavaObservationDateTime, int ObservationTimeZone, String AuditTrailAccessType, boolean DistributeReport, byte[] ReportContentBuffer, String UserName,
		String SOPInstanceUID);

		public native int CheckAndMergePatientRecords(String UserName, int PatientIDMergeFrom, int PatientVersionMergeFrom, int PatientIDMergeTo, int PatientVersionMergeTo);	

	public native int CheckAndMergeStudyRecords(String UserName, String StudyInstanceUIDMergeFrom, int StudyVersionMergeFrom, String StudyInstanceUIDMergeTo, int StudyVersionMergeTo);	

	public native int GroupStudies(String PatientID, String IssuerOfPatientID, String AccessionNumbers, String UserName);	
	
	public native String ApplyMask(String UnformattedText, String SimpleMask, String ExtendedMask);

    public native int  GetMirthListeningPort();	
    public native void SetMirthListeningPort(int ListeningPort); 
	
	public native int PlaceOrder(int RefPhyInternalUserID, String PatientLastName, String PatientFirstName, long PatientBirthDate, String PatientPhone, 
		int FacilityInternalID, int RefFacilityInternalID, int[] StudyTypeIDList, String AuthorizationNumber, long RequestedDateTime,
		boolean RequestedTimeSpecified, String Comments);	
	
	public native int PlaceOrderEx(int RefPhyInternalUserID, String PatientLastName, String PatientFirstName, long PatientBirthDate, String PatientPhone, 
			int FacilityInternalID, int RefFacilityInternalID, int[] StudyTypeIDList, String AuthorizationNumber, long RequestedDateTime,
			boolean RequestedTimeSpecified, String Comments, byte[] AttachmentBytes, String AttachmentFileName, String Subject);
	
	public String LoadDLL(String FileName)
	{
		//System.loadLibrary(libname)
		String Result = null;
		try
		{
			System.load(FileName);
		}
		catch (UnsatisfiedLinkError e) 
		{
			Result = "Native code library failed to load.\n" + e;
			System.err.println(Result);
		}	
		
		return Result;
	}

	public String LoadLibrary(String LibraryName)
	{
		String Result = null;
		try
		{			
			System.loadLibrary(LibraryName);
		}
		catch (UnsatisfiedLinkError e) 
		{
			Result = "Native code library failed to load.\n" + e;
			System.err.println(Result);
		}	
		
		return Result;
	}
	
	public static void main(String[] args) 
	{
		// TODO Auto-generated method stub
		
		/*MirthUtilities DLLTest = new MirthUtilities();
		
		DLLTest.LoadDLL("c:/program files/ramsoft/gateway4/RSHL7Mirth.dll");
		
		DLLTest.Initialize("C:\\Program Files\\RamSoft\\gateway4\\MirthDLLLog\\MirthDLL.log", 
				"C:\\Program Files\\RamSoft\\gateway4\\MirthDLLLog\\MirthPRDLL.log", 10);
		
		int[] OperationStatus = {1};
		DLLTest.TestArrayOutput(OperationStatus);
		
		String[] ObserverName = {""};
		String[] ObserverID = {""};
		String[] ObserverOrganization = {""};
		//DLLTest.Test(ObserverName, ObserverID, ObserverOrganization);
		
		//DLLTest.CheckAndMergePatientRecords("System", 1, 0, 2, 1);
		
		long[] ObservationDateTime = {0};
		int[] ObservationTimeZone = {0};		 
		byte[] result = DLLTest.GetReportContent("1.2.124.113540.1.4.4248805982.10984.1313168547.50", 25, OperationStatus, 
			ObservationDateTime, ObservationTimeZone, ObserverName, ObserverID, ObserverOrganization);
		
		DateFormat df = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
		System.out.println("Formatted date: " + df.format(ObservationDateTime[0])); 
		
		try
		{
			FileOutputStream fos = new FileOutputStream("C:\\Program Files\\RamSoft\\gateway4\\MirthDLLLog\\ReportOutput.txt");
			fos.write(result);
			fos.close(); 
		}     
		catch (FileNotFoundException ex)     
		{      
			System.out.println("FileNotFoundException : " + ex);     
		}
	    catch (IOException ioe)     
	    {      
	    	System.out.println("IOException : " + ioe);     
	    }
	    
	    byte[] ReportBuffer = null;
	    
	    try
		{
			FileInputStream fis = new FileInputStream("C:\\Program Files\\RamSoft\\gateway4\\MirthDLLLog\\TestInputTextReport.txt");
			ReportBuffer = new byte[fis.available()];
			fis.read(ReportBuffer);
			fis.close(); 

		    DLLTest.SetReportContent("1.2.124.113540.0.0.3.614", 2, 1, "Diagnostic Report", "", "ALEX", "62", "Ramsoft", System.currentTimeMillis(), -300, "Patient Audit", ReportBuffer);
		}     
		catch (FileNotFoundException ex)     
		{      
			System.out.println("FileNotFoundException : " + ex);     
		}
	    catch (IOException ioe)     
	    {      
	    	System.out.println("IOException : " + ioe);     
	    }
   
		DLLTest.Finalize();*/
	}
}
