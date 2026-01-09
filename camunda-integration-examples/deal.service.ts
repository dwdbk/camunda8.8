import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

const API_URL = 'http://localhost:8080/api/deals';

export interface DealProcessResponse {
  processInstanceId: string;
  currentStep: string;
  taskId?: string;
}

export interface ProcessStepResponse {
  processInstanceId: string;
  currentStep: string;
  taskId?: string;
  message?: string;
}

export interface ProcessStatusResponse {
  processInstanceId: string;
  currentStep: string;
  state: string;
  completed: boolean;
}

export interface DealCompletionResponse {
  success: boolean;
  processInstanceId: string;
  summary: {
    dealName: string;
    partiesCount: number;
    contactsCount: number;
  };
}

export interface DealInfo {
  dealName: string;
  dealType: string;
  description?: string;
  startDate?: string;
  endDate?: string;
  value?: number;
}

export interface Party {
  name: string;
  type: string;
  role: string;
  contactEmail?: string;
}

export interface Parties {
  parties: Party[];
}

export interface Contact {
  firstName: string;
  lastName: string;
  email: string;
  phone?: string;
  role?: string;
}

export interface Contacts {
  contacts: Contact[];
}

/**
 * Service for interacting with deal process endpoints.
 * All requests are automatically authenticated via HTTP interceptor.
 */
@Injectable({
  providedIn: 'root'
})
export class DealService {
  
  constructor(private http: HttpClient) {}
  
  /**
   * Initialize a new deal process.
   * Returns process instance ID and current step information.
   */
  initiateDeal(): Observable<DealProcessResponse> {
    return this.http.post<DealProcessResponse>(`${API_URL}/initiate`, {});
  }
  
  /**
   * Submit deal information form.
   * Advances process to next step (parties).
   */
  submitDealInfo(processInstanceId: string, dealInfo: DealInfo): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${API_URL}/${processInstanceId}/deal-info`,
      dealInfo
    );
  }
  
  /**
   * Submit parties form.
   * Advances process to next step (contacts).
   */
  submitParties(processInstanceId: string, parties: Parties): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${API_URL}/${processInstanceId}/parties`,
      parties
    );
  }
  
  /**
   * Submit contacts form.
   * Advances process to completion step.
   */
  submitContacts(processInstanceId: string, contacts: Contacts): Observable<ProcessStepResponse> {
    return this.http.post<ProcessStepResponse>(
      `${API_URL}/${processInstanceId}/contacts`,
      contacts
    );
  }
  
  /**
   * Complete the deal process.
   * Finalizes the process instance.
   */
  completeDeal(processInstanceId: string): Observable<DealCompletionResponse> {
    return this.http.post<DealCompletionResponse>(
      `${API_URL}/${processInstanceId}/complete`,
      {}
    );
  }
  
  /**
   * Get current process status.
   * Useful for checking process state and current step.
   */
  getProcessStatus(processInstanceId: string): Observable<ProcessStatusResponse> {
    return this.http.get<ProcessStatusResponse>(
      `${API_URL}/${processInstanceId}/status`
    );
  }
}
