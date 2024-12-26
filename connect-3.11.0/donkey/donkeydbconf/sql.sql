 SELECT t.create_date, t.ref_doc_num, g.do_number,         
         s.ref_document_code, t.transaction_type, iv.counter_code, iv.inventory_name,     
             tr.src_inventory_id, tr.des_inventory_id,      
                 t.note, td.sku, i.label_code, i.old_sku,      
                     ia.attribute_value, i.upc, i.item_name, td.quantity, rr.return_to_inventory  
                     FROM transaction_tbl t  
     INNER JOIN transaction_detail_tbl td ON t.transaction_id = td.transaction_id 
     INNER JOIN inventory_tbl iv ON iv.inventory_id = t.inventory_id 
     LEFT JOIN goods_receipt_tbl g ON g.receipt_number = t.ref_doc_num 
     LEFT JOIN sale_receipt_tbl s ON s.receipt_number = t.ref_doc_num 
     INNER JOIN item_tbl i ON i.sku = td.sku  
     LEFT JOIN transfer_tbl tr ON t.ref_doc_num = tr.transfer_number AND t.transaction_type = 1 
     LEFT JOIN item_attribute_tbl ia ON ia.sku = td.sku AND ia.attribute_code = "CATEGORY" 
     LEFT JOIN return_request_tbl rr ON rr.request_number = t.ref_doc_num ;